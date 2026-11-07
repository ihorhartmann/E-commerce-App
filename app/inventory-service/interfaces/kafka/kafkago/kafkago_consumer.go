package kafkago

import (
	"context"
	"errors"
	"inventoryservice/application/port/common/kafka"
	"inventoryservice/application/port/common/logging"
	"inventoryservice/domain/apperror"
	inkafka "inventoryservice/interfaces/kafka"
	"io"
	"log/slog"
	"sync"
	"sync/atomic"
	"time"

	"inventoryservice/interfaces/kafka/common"

	"github.com/google/uuid"
	kafkago "github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel/trace"
)

const (
	reconnectBackoff = 2 * time.Second
	// startupJoinTimeout is how long a new reader gets to join its consumer group - longer than a rebalance with other
	// members takes, kafka-go's rebalance timeout being 30s
	startupJoinTimeout = 30 * time.Second
	joinPollInterval   = 200 * time.Millisecond
)

type messageHandler = func(ctx context.Context, messageType kafka.MessageType, messageID uuid.UUID, correlationID string, payload []byte) error

type kafkaGoMessageConsumer struct {
	common.KafkaMessageConsumer
	readerConfig       kafkago.ReaderConfig
	reader             *kafkago.Reader
	readerMu           sync.Mutex
	wg                 sync.WaitGroup
	closing            atomic.Bool
	groupID            string
	deadLetterProducer deadLetterProducer

	retryDelays []time.Duration
	// retryCtx is cancelled by Close, to cut short the retry back-offs - both for the message handler
	// and for dead-letter publishing - that would otherwise keep Close waiting.
	retryCtx    context.Context
	cancelRetry context.CancelFunc
}

func NewKafkaGoMessageConsumer(brokers []string, topic, groupID string) common.KafkaMessageConsumer {
	readerConfig := kafkago.ReaderConfig{
		Brokers: brokers,
		Topic:   topic,
		GroupID: groupID,
	}

	retryCtx, cancelRetry := context.WithCancel(context.Background())

	return &kafkaGoMessageConsumer{
		readerConfig:       readerConfig,
		reader:             kafkago.NewReader(readerConfig),
		groupID:            groupID,
		deadLetterProducer: newDeadLetterRetryableProducer(newKafkaGoDeadLetterProducer(brokers, topic+kafka.DLTSuffix), deadLetterRetryInterval),
		retryDelays:        backoffDelays(maxRetries, initialBackoff),
		retryCtx:           retryCtx,
		cancelRetry:        cancelRetry,
	}
}

func (c *kafkaGoMessageConsumer) ConsumeAsync(handle func(ctx context.Context, messageType kafka.MessageType, messageId uuid.UUID, correlationId string, payload []byte) error) {
	c.wg.Go(func() {
		// A new Reader must join its consumer group within startupJoinTimeout, or it is thrown away and a new one
		// started: kafka-go's Reader can get stuck forever if the broker isn't ready yet when the app starts - it
		// won't error, it just waits. Once it has joined and got its partitions, the broker is proven reachable, and
		// fetches wait with no time limit: a quiet topic is normal and must not cause a reconnect.
		joined := false

		for {
			reader := c.lockedReader()

			if !joined {
				if !c.awaitJoin(reader) {
					if c.closing.Load() {
						return
					}
					slog.Warn("kafka consumer didn't join its group during startup, reconnecting")
					if !c.reconnect(reader) {
						return
					}
					continue
				}
				joined = true
			}

			message, err := reader.FetchMessage(context.Background())
			if err != nil {
				if errors.Is(err, io.EOF) || errors.Is(err, kafkago.ErrGroupClosed) {
					if !c.closing.Load() {
						slog.Error("kafka consumer closed unexpectedly", "error", err)
					}
					return
				}

				slog.Error("kafka consumer read error, reconnecting", "error", err)
				if !c.reconnect(reader) {
					return
				}
				// the new reader has to join the group first
				joined = false
				continue
			}

			if !c.process(c.retryCtx, message, handle) {
				return
			}

			if err = reader.CommitMessages(context.Background(), message); err != nil {
				slog.Error("kafka commit error", "error", err)
			}
		}
	})
}

// process handles message, retrying transient failures, and dead-letters it if it still fails or
// fails in a way retrying can't fix (including a message with invalid headers). It returns true once
// the message is settled - handled or dead-lettered - and may be committed. It returns false if ctx
// is done first, i.e. the consumer is shutting down: the message must then not be committed, so that
// it is redelivered instead of being lost.
//
// The whole of it - every attempt and the dead-lettering - is one consumer span, continuing the trace of whoever sent
// the message.
func (c *kafkaGoMessageConsumer) process(ctx context.Context, message kafkago.Message, handle messageHandler) bool {
	ctx, span := startProcessSpan(ctx, message, c.groupID)
	defer span.End()

	start := time.Now()
	messageType, messageID, correlationID, err := parseHeaders(message.Headers)
	if err != nil {
		rawMessageType, _ := headerValue(message.Headers, kafka.MessageTypeHeader)
		recordAttemptMetric(ctx, message.Topic, kafka.MessageType(rawMessageType), start, err)
		slog.WarnContext(ctx, "kafka message rejected", apperror.LogAttr(err))
		return c.sendDeadLetter(ctx, message, err)
	}

	// the message's ids in every line logged while it is handled, here and in the handler
	ctx = logging.WithAttrs(ctx,
		slog.String(kafka.MessageTypeHeader, messageType.String()),
		slog.String(kafka.MessageIDHeader, messageID.String()),
		slog.String(kafka.CorrelationIDHeader, correlationID),
	)
	slog.DebugContext(ctx, "received kafka message")

	// the handler's context carries the span and the ids but not ctx's cancellation: a shutdown cuts the retry
	// back-offs short, never a handler in the middle of its work
	handlerCtx := context.WithoutCancel(ctx)
	err = runWithRetry(ctx, c.retryDelays, func() error {
		attemptStart := time.Now()
		err = handleSafely(handlerCtx, handle, messageType, messageID, correlationID, message.Value)
		recordAttemptMetric(ctx, message.Topic, messageType, attemptStart, err)
		if err != nil {
			logFailure(ctx, err)
		}
		return err
	})
	if err == nil {
		slog.InfoContext(ctx, "successfully processed kafka message")
		return true
	}
	if ctx.Err() != nil {
		return false
	}

	return c.sendDeadLetter(ctx, message, err)
}

// logFailure logs a failed attempt at handling a message as a warning: a permanent failure is rejected - dead-lettered at
// once - a transient one retried. The error is logged once, when the message is dead-lettered.
func logFailure(ctx context.Context, err error) {
	if apperror.OutermostError(err).Category().Permanent() {
		slog.WarnContext(ctx, "kafka message rejected", apperror.LogAttr(err))
		return
	}
	slog.WarnContext(ctx, "kafka message handling failed", apperror.LogAttr(err))
}

// handleSafely runs handle for one message, turning a panic in it into an internal error - retried
// and then dead-lettered like any other - instead of a crashed consumer.
func handleSafely(ctx context.Context, handle messageHandler, messageType kafka.MessageType, messageID uuid.UUID, correlationID string, payload []byte) (err error) {
	defer func() {
		if recovered := recover(); recovered != nil {
			err = apperror.FromPanic(recovered)
		}
	}()

	return handle(ctx, messageType, messageID, correlationID, payload)
}

// sendDeadLetter publishes message to its dead-letter topic - c.deadLetterProducer keeps retrying on its
// own until that succeeds, so the message is never skipped: a dead-letter topic that is down (or
// missing) blocks the partition instead of losing messages. It returns false if ctx is done first.
func (c *kafkaGoMessageConsumer) sendDeadLetter(ctx context.Context, message kafkago.Message, cause error) bool {
	deadLetter := newDeadLetterMessage(message, c.groupID, cause)

	if err := c.deadLetterProducer.Send(ctx, deadLetter); err != nil {
		return false
	}

	slog.ErrorContext(ctx, "kafka message dead-lettered", "topic", message.Topic, "partition", message.Partition, "offset", message.Offset, apperror.LogAttr(cause))
	messageType, _ := headerValue(message.Headers, kafka.MessageTypeHeader)
	recordDeadLetteredMetric(ctx, message.Topic, kafka.MessageType(messageType))
	markDeadLettered(trace.SpanFromContext(ctx), cause)
	return true
}

// awaitJoin waits up to startupJoinTimeout for reader to join its consumer group and get its partitions - a Reader
// starts joining as soon as it is created, and counts a rebalance in its stats once it has joined. It returns false
// if the time runs out, or if the consumer is closed meanwhile. Reading the stats resets them; nothing else reads them.
func (c *kafkaGoMessageConsumer) awaitJoin(reader *kafkago.Reader) bool {
	deadline := time.Now().Add(startupJoinTimeout)
	for time.Now().Before(deadline) {
		if reader.Stats().Rebalances > 0 {
			return true
		}
		if err := sleep(c.retryCtx, joinPollInterval); err != nil {
			return false
		}
	}
	return false
}

// lockedReader reads c.reader under readerMu, since Close (called from another goroutine) can run
// at the same time reconnect is replacing c.reader on the consume goroutine.
func (c *kafkaGoMessageConsumer) lockedReader() *kafkago.Reader {
	c.readerMu.Lock()
	defer c.readerMu.Unlock()
	return c.reader
}

// reconnect replaces a reader that failed to make progress (a fetch error, or - during startup - not
// joining its consumer group within startupJoinTimeout) with a fresh one on the same topic/group,
// after a short backoff.
// Returns false if the consumer was closed during the backoff, telling the caller to stop instead of
// retrying.
func (c *kafkaGoMessageConsumer) reconnect(broken *kafkago.Reader) bool {
	_ = broken.Close()
	time.Sleep(reconnectBackoff)

	c.readerMu.Lock()
	defer c.readerMu.Unlock()

	if c.closing.Load() {
		return false
	}
	c.reader = kafkago.NewReader(c.readerConfig)
	return true
}

func (c *kafkaGoMessageConsumer) Close() error {
	c.closing.Store(true)
	c.cancelRetry()

	err := c.lockedReader().Close()
	c.wg.Wait()
	deadLettersErr := c.deadLetterProducer.Close()

	if err = errors.Join(err, deadLettersErr); err != nil {
		return err
	}

	slog.Info("kafka consumer closed successfully")
	return nil
}

// parseHeaders extracts the headers a handler actually relies on, all required: messageType for
// routing, messageId - which must be a UUID - as the idempotency key, and correlationId for tracing.
func parseHeaders(headers []kafkago.Header) (messageType kafka.MessageType, messageID uuid.UUID, correlationID string, err error) {
	var rawMessageID string
	for _, header := range headers {
		switch header.Key {
		case kafka.MessageTypeHeader:
			messageType = kafka.MessageType(header.Value)
		case kafka.MessageIDHeader:
			rawMessageID = string(header.Value)
		case kafka.CorrelationIDHeader:
			correlationID = string(header.Value)
		}
	}

	if messageType == "" {
		return "", uuid.Nil, "", inkafka.ErrInvalidMessageHeaders.WithInternalMessage("missing messageType header")
	}
	if rawMessageID == "" {
		return "", uuid.Nil, "", inkafka.ErrInvalidMessageHeaders.WithInternalMessage("missing messageId header")
	}
	if correlationID == "" {
		return "", uuid.Nil, "", inkafka.ErrInvalidMessageHeaders.WithInternalMessage("missing correlationId header")
	}

	messageID, err = uuid.Parse(rawMessageID)
	if err != nil {
		return "", uuid.Nil, "", inkafka.ErrInvalidMessageHeaders.
			WithInternalMessage("messageId is not a UUID").
			WithAttrs(slog.String("messageID", rawMessageID)).
			WithCause(err)
	}

	return messageType, messageID, correlationID, nil
}

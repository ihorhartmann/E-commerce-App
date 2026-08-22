package kafkago

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"shippingservice/adapter/common/kafka"
	"sync"
	"sync/atomic"
	"time"

	"shippingservice/adapter/in/kafka/common"

	kafkago "github.com/segmentio/kafka-go"
)

const (
	reconnectBackoff    = 2 * time.Second
	startupFetchTimeout = 5 * time.Second
)

type kafkaGoMessageConsumer struct {
	common.KafkaMessageConsumer
	readerConfig kafkago.ReaderConfig
	reader       *kafkago.Reader
	readerMu     sync.Mutex
	wg           sync.WaitGroup
	closing      atomic.Bool
}

func NewKafkaGoMessageConsumer(brokers []string, topic, groupID string) common.KafkaMessageConsumer {
	readerConfig := kafkago.ReaderConfig{
		Brokers: brokers,
		Topic:   topic,
		GroupID: groupID,
	}

	return &kafkaGoMessageConsumer{
		readerConfig: readerConfig,
		reader:       kafkago.NewReader(readerConfig),
	}
}

func (c *kafkaGoMessageConsumer) ConsumeAsync(handle func(messageType kafka.MessageType, messageId string, correlationId string, payload []byte) error) {
	c.wg.Go(func() {
		ctx := context.Background()

		// Until the first message arrives, each fetch has a time limit, and if it runs out we
		// throw away the Reader and start a new one. This is needed because kafka-go's Reader
		// can get stuck forever if the broker isn't ready yet when the app starts - it won't
		// error, it just waits. Once one message arrives, we know the Reader works, so later
		// fetches wait with no time limit. Long quiet gaps between messages are normal and must
		// not cause a reconnect.
		ready := false

		for {
			reader := c.lockedReader()

			fetchCtx, cancel := c.fetchContext(ctx, ready)
			message, err := reader.FetchMessage(fetchCtx)
			cancel()

			if err != nil {
				if errors.Is(err, io.EOF) || errors.Is(err, kafkago.ErrGroupClosed) {
					if !c.closing.Load() {
						slog.Error("kafka consumer closed unexpectedly", "error", err)
					}
					return
				}

				if !ready && errors.Is(err, context.DeadlineExceeded) {
					slog.Warn("kafka consumer made no progress during startup, reconnecting")
				} else {
					slog.Error("kafka consumer read error, reconnecting", "error", err)
				}

				if !c.reconnect(reader) {
					return
				}
				continue
			}

			ready = true

			messageType, messageID, correlationID, err := parseHeaders(message.Headers)
			if err != nil {
				slog.Error("kafka message header validation error", "error", err)
				continue
			}

			slog.Debug("received kafka message", "messageType", messageType, "messageId", messageID, "correlationId", correlationID)

			if err = handle(messageType, messageID, correlationID, message.Value); err != nil {
				slog.Error("kafka message handling error", "messageType", messageType, "correlationId", correlationID, "error", err)
				continue
			}
			slog.Info("successfully processed kafka message", "messageType", messageType, "correlationId", correlationID)

			if err = reader.CommitMessages(ctx, message); err != nil {
				slog.Error("kafka commit error", "error", err)
			}
		}
	})
}

// fetchContext returns the context.Context to fetch with and its cancel func. Before the reader
// is proven healthy (ready == false), the fetch is bounded by startupFetchTimeout; afterwards it
// blocks indefinitely, so cancel is a no-op.
func (c *kafkaGoMessageConsumer) fetchContext(ctx context.Context, ready bool) (context.Context, context.CancelFunc) {
	if ready {
		return ctx, func() {}
	}
	return context.WithTimeout(ctx, startupFetchTimeout)
}

// lockedReader reads c.reader under readerMu, since Close (called from another goroutine) can run
// at the same time reconnect is replacing c.reader on the consume goroutine.
func (c *kafkaGoMessageConsumer) lockedReader() *kafkago.Reader {
	c.readerMu.Lock()
	defer c.readerMu.Unlock()
	return c.reader
}

// reconnect replaces a reader that failed to make progress (a fetch error, or - during startup - a
// fetch that neither erred nor returned a message within startupFetchTimeout) with a fresh one on
// the same topic/group, after a short backoff.
// Returns false if the consumer was closed during the backoff, telling the caller to stop instead
// of retrying.
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

	err := c.lockedReader().Close()
	c.wg.Wait()
	if err != nil {
		return err
	}

	slog.Info("kafka consumer closed successfully")
	return nil
}

// parseHeaders extracts the headers a handler actually relies on - messageType for routing and
// correlationId for tracing, both required - and messageId, which is carried through as an opaque
// string without being validated as a UUID.
func parseHeaders(headers []kafkago.Header) (messageType kafka.MessageType, messageID string, correlationID string, err error) {
	for _, header := range headers {
		switch header.Key {
		case kafka.MessageTypeHeader:
			messageType = kafka.MessageType(header.Value)
		case kafka.MessageIDHeader:
			messageID = string(header.Value)
		case kafka.CorrelationIDHeader:
			correlationID = string(header.Value)
		}
	}

	if messageType == "" {
		return "", "", "", errors.New("missing messageType header")
	}
	if messageID == "" {
		return "", "", "", errors.New("missing messageID header")
	}
	if correlationID == "" {
		return "", "", "", errors.New("missing correlationId header")
	}

	return messageType, messageID, correlationID, nil
}

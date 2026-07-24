package kafkago

import (
	"context"
	"errors"
	"inventoryservice/application/ports/common/kafka"
	"io"
	"log/slog"
	"sync"
	"sync/atomic"

	"inventoryservice/interfaces/kafka/common"

	kafkago "github.com/segmentio/kafka-go"
)

type messageConsumer struct {
	reader  *kafkago.Reader
	wg      sync.WaitGroup
	closing atomic.Bool
}

func NewKafkaGoMessageConsumer(brokers []string, topic, groupID string) common.KafkaMessageConsumer {
	reader := kafkago.NewReader(kafkago.ReaderConfig{
		Brokers: brokers,
		Topic:   topic,
		GroupID: groupID,
	})

	return &messageConsumer{reader: reader}
}

func (c *messageConsumer) ConsumeAsync(handle func(messageType kafka.MessageType, messageId string, correlationId string, payload []byte) error) {
	c.wg.Go(func() {
		ctx := context.Background()

		for {
			message, err := c.reader.FetchMessage(ctx)
			if err != nil {
				if errors.Is(err, io.EOF) || errors.Is(err, kafkago.ErrGroupClosed) {
					if !c.closing.Load() {
						slog.Error("kafka consumer closed unexpectedly", "error", err)
					}
					return
				}
				slog.Error("kafka consumer read error", "error", err)
				continue
			}

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

			if err = c.reader.CommitMessages(ctx, message); err != nil {
				slog.Error("kafka commit error", "error", err)
			}
		}
	})
}

func (c *messageConsumer) Close() error {
	c.closing.Store(true)

	err := c.reader.Close()
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

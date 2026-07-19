package kafkago

import (
	"context"
	"errors"
	"io"
	"log/slog"

	"inventoryservice/interfaceadapters/common/kafka"
	"inventoryservice/interfaceadapters/in/kafka/common"

	kafkago "github.com/segmentio/kafka-go"
)

type messageConsumer struct {
	reader *kafkago.Reader
}

func NewKafkaGoMessageConsumer(brokers []string, topic, groupID string) common.KafkaMessageConsumer {
	reader := kafkago.NewReader(kafkago.ReaderConfig{
		Brokers: brokers,
		Topic:   topic,
		GroupID: groupID,
	})

	return &messageConsumer{reader: reader}
}

func (c *messageConsumer) ConsumeAsync(handle func(eventType kafka.EventType, eventId string, correlationId string, payload []byte) error) {
	go func() {
		ctx := context.Background()

		for {
			message, err := c.reader.FetchMessage(ctx)
			if err != nil {
				if errors.Is(err, io.EOF) || errors.Is(err, kafkago.ErrGroupClosed) {
					return
				}
				slog.Error("kafka consumer read error", "error", err)
				continue
			}

			eventType, eventID, correlationID, err := parseHeaders(message.Headers)
			if err != nil {
				slog.Error("kafka message header validation error", "error", err)
				continue
			}

			slog.Debug("received kafka message", "eventType", eventType, "eventId", eventID, "correlationId", correlationID)

			if err = handle(eventType, eventID, correlationID, message.Value); err != nil {
				slog.Error("kafka message handling error", "eventType", eventType, "correlationId", correlationID, "error", err)
				continue
			}
			slog.Info("successfully processed kafka message", "eventType", eventType, "correlationId", correlationID)

			if err = c.reader.CommitMessages(ctx, message); err != nil {
				slog.Error("kafka commit error", "error", err)
			}
		}
	}()
}

func (c *messageConsumer) Close() error {
	return c.reader.Close()
}

// parseHeaders extracts the headers a handler actually relies on - eventType for routing and
// correlationId for tracing, both required - and eventId, which is carried through as an opaque
// string without being validated as a UUID.
func parseHeaders(headers []kafkago.Header) (eventType kafka.EventType, eventID string, correlationID string, err error) {
	for _, header := range headers {
		switch header.Key {
		case eventTypeHeader:
			eventType = kafka.EventType(header.Value)
		case eventIDHeader:
			eventID = string(header.Value)
		case correlationIDHeader:
			correlationID = string(header.Value)
		}
	}

	if eventType == "" {
		return "", "", "", errors.New("missing eventType header")
	}
	if eventID == "" {
		return "", "", "", errors.New("missing eventID header")
	}
	if correlationID == "" {
		return "", "", "", errors.New("missing correlationId header")
	}

	return eventType, eventID, correlationID, nil
}

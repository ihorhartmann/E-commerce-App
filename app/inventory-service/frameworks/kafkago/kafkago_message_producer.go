package kafkago

import (
	"context"
	"encoding/json"
	"log/slog"

	"inventoryservice/interfaceadapters/common/kafka"
	"inventoryservice/interfaceadapters/out/kafka/common"

	"github.com/google/uuid"
	kafkago "github.com/segmentio/kafka-go"
)

type messageProducer struct {
	writer *kafkago.Writer
}

func NewKafkaGoMessageProducer(brokers []string, topic string) common.KafkaMessageProducer {
	writer := &kafkago.Writer{
		Addr:     kafkago.TCP(brokers...),
		Topic:    topic,
		Balancer: &kafkago.LeastBytes{},
	}

	return &messageProducer{writer: writer}
}

func (p *messageProducer) Send(eventType kafka.EventType, correlationId string, key string, payload any) error {
	value, err := json.Marshal(payload)
	if err != nil {
		return err
	}

	slog.Debug("sending kafka message", "eventType", eventType, "correlationId", correlationId, "key", key)

	err = p.writer.WriteMessages(context.Background(), kafkago.Message{
		Key: []byte(key),
		Headers: []kafkago.Header{
			{Key: eventTypeHeader, Value: []byte(eventType.String())},
			{Key: correlationIDHeader, Value: []byte(correlationId)},
			{Key: eventIDHeader, Value: []byte(uuid.New().String())},
		},
		Value: value,
	})
	if err != nil {
		return err
	}

	slog.Info("successfully sent kafka message", "eventType", eventType, "correlationId", correlationId)

	return nil
}

func (p *messageProducer) Close() error {
	return p.writer.Close()
}

package kafkago

import (
	"context"
	"encoding/json"
	"inventoryservice/application/port/common/kafka"
	"log/slog"
	"time"

	"inventoryservice/infrastructure/kafka/common"

	"github.com/google/uuid"
	kafkago "github.com/segmentio/kafka-go"
)

type kafkaGoMessageProducer struct {
	common.KafkaMessageProducer
	writer *kafkago.Writer
}

func NewKafkaGoMessageProducer(brokers []string, topic string) common.KafkaMessageProducer {
	writer := &kafkago.Writer{
		Addr:         kafkago.TCP(brokers...),
		Topic:        topic,
		Balancer:     &kafkago.Hash{},
		RequiredAcks: kafkago.RequireOne,
		BatchSize:    100,
		BatchTimeout: 200 * time.Millisecond,
	}

	return &kafkaGoMessageProducer{writer: writer}
}

func (p *kafkaGoMessageProducer) Send(messageType kafka.MessageType, correlationId string, key string, payload any) error {
	value, err := json.Marshal(payload)
	if err != nil {
		return err
	}

	slog.Debug("sending kafka message", "messageType", messageType, "correlationId", correlationId, "key", key)

	err = p.writer.WriteMessages(context.Background(), kafkago.Message{
		Key: []byte(key),
		Headers: []kafkago.Header{
			{Key: kafka.MessageTypeHeader, Value: []byte(messageType.String())},
			{Key: kafka.CorrelationIDHeader, Value: []byte(correlationId)},
			{Key: kafka.MessageIDHeader, Value: []byte(uuid.New().String())},
		},
		Value: value,
	})
	if err != nil {
		return err
	}

	slog.Info("successfully sent kafka message", "messageType", messageType, "correlationId", correlationId)

	return nil
}

func (p *kafkaGoMessageProducer) Close() error {
	return p.writer.Close()
}

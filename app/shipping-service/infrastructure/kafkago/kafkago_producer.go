package kafkago

import (
	"context"
	"log/slog"
	"shippingservice/adapter/common/kafka"
	"shippingservice/adapter/out/kafka/common"
	"shippingservice/application/port/out/outbox/dto"
	"time"

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

func (p *kafkaGoMessageProducer) SendBatch(messages []*dto.OutboxMessage) error {
	slog.Debug("sending kafka message batch", "count", len(messages))

	if err := p.writer.WriteMessages(context.Background(), toLibMessages(messages)...); err != nil {
		return err
	}

	slog.Info("successfully sent kafka message batch", "count", len(messages))

	return nil
}

func toLibMessages(messages []*dto.OutboxMessage) []kafkago.Message {
	libMessages := make([]kafkago.Message, len(messages))
	for i, message := range messages {
		libMessages[i] = kafkago.Message{
			Key: []byte(message.Key),
			Headers: []kafkago.Header{
				{Key: kafka.MessageTypeHeader, Value: []byte(message.MessageType)},
				{Key: kafka.CorrelationIDHeader, Value: []byte(message.CorrelationID)},
				{Key: kafka.MessageIDHeader, Value: []byte(message.MessageID)},
			},
			Value: message.Payload,
		}
	}

	return libMessages
}

func (p *kafkaGoMessageProducer) Close() error {
	return p.writer.Close()
}

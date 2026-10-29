package kafkago

import (
	"context"
	"log/slog"
	"shippingservice/adapter/common/kafka"
	"shippingservice/adapter/out/kafka/common"
	"shippingservice/application/port/out/outbox/dto"
	"time"

	kafkago "github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel/trace"
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
		BatchTimeout: 5 * time.Millisecond,
	}

	return &kafkaGoMessageProducer{writer: writer}
}

// SendBatch publishes messages in one write, each in a producer span of its own - a child of the span it was written to
// the outbox in, so its trace goes on through Kafka even though a batch mixes messages of many traces.
func (p *kafkaGoMessageProducer) SendBatch(messages []*dto.OutboxMessage) error {
	slog.Debug("sending kafka message batch", "count", len(messages))

	libMessages := toLibMessages(messages)
	spans := make([]trace.Span, len(messages))
	for i, message := range messages {
		spans[i] = startSendSpan(p.writer.Topic, message.Traceparent, &libMessages[i], message.MessageType, message.MessageID.String(), message.CorrelationID)
	}

	err := p.writer.WriteMessages(context.Background(), libMessages...)
	endSendSpans(spans, err)
	if err != nil {
		return ErrPublishFailure.
			WithAttrs(slog.String("topic", p.writer.Topic), slog.Int("count", len(messages))).
			WithCause(err)
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
				{Key: kafka.MessageIDHeader, Value: []byte(message.MessageID.String())},
			},
			Value: message.Payload,
		}
	}

	return libMessages
}

func (p *kafkaGoMessageProducer) Close() error {
	return p.writer.Close()
}

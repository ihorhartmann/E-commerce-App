package kafkago

import (
	"shippingservice/adapter/common/kafka"
	"shippingservice/adapter/in/kafka/common"

	"github.com/google/uuid"
)

type messageConsumer struct {
	common.KafkaMessageConsumer
}

func NewStubMessageConsumer() common.KafkaMessageConsumer {
	return &messageConsumer{}
}

func (c *messageConsumer) ConsumeAsync(_ func(eventType kafka.EventType, eventId, correlationId uuid.UUID, payload []byte) error) {
}

func (c *messageConsumer) Close() error {
	return nil
}

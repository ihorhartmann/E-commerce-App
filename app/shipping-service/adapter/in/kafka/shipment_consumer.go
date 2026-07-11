package kafka

import (
	commonkafka "shippingservice/adapter/common/kafka"
	"shippingservice/adapter/in/kafka/common"

	"github.com/google/uuid"
)

type ShipmentConsumer struct {
	consumer common.KafkaMessageConsumer
}

func NewShipmentConsumer(consumer common.KafkaMessageConsumer) *ShipmentConsumer {
	return &ShipmentConsumer{consumer: consumer}
}

func (c *ShipmentConsumer) Start() {
	c.consumer.ConsumeAsync(func(_ commonkafka.EventType, _, _ uuid.UUID, _ []byte) error {
		return nil
	})
}

func (c *ShipmentConsumer) Close() error {
	return c.consumer.Close()
}

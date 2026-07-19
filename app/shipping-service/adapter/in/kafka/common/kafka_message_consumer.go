package common

import (
	"shippingservice/adapter/common/kafka"
)

type KafkaMessageConsumer interface {
	ConsumeAsync(func(eventType kafka.EventType, eventId string, correlationId string, payload []byte) error)
	Close() error
}

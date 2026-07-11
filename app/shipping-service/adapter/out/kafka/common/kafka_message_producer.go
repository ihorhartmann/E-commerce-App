package common

import (
	"shippingservice/adapter/common/kafka"

	"github.com/google/uuid"
)

type KafkaMessageProducer interface {
	Send(eventType kafka.EventType, correlationId uuid.UUID, payload any) error
	Close() error
}

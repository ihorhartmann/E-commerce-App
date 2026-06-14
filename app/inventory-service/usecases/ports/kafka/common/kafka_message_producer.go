package common

import (
	"github.com/google/uuid"
)

type KafkaMessageProducer interface {
	Send(eventType EventType, correlationId uuid.UUID, payload any) error
	Close() error
}

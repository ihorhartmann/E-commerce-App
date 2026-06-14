package common

import (
	"github.com/google/uuid"
)

type KafkaMessageConsumer interface {
	ConsumeAsync(func(eventType EventType, eventId, correlationId uuid.UUID, payload []byte) error)
	Close() error
}

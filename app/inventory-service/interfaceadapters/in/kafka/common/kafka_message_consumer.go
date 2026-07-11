package common

import (
	"inventoryservice/interfaceadapters/common/kafka"

	"github.com/google/uuid"
)

type KafkaMessageConsumer interface {
	ConsumeAsync(func(eventType kafka.EventType, eventId, correlationId uuid.UUID, payload []byte) error)
	Close() error
}

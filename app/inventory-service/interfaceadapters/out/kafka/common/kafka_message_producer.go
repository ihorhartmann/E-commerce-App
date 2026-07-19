package common

import (
	"inventoryservice/interfaceadapters/common/kafka"
)

type KafkaMessageProducer interface {
	// Send publishes payload for eventType, with correlationId carried in the message headers
	// for tracing and key used for partitioning (should be the aggregate id, e.g. orderId).
	Send(eventType kafka.EventType, correlationId string, key string, payload any) error
	Close() error
}

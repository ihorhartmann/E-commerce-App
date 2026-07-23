package common

import (
	"shippingservice/adapter/common/kafka"
)

type KafkaMessageProducer interface {
	// Send publishes payload for messageType, with correlationId carried in the message headers
	// for tracing and key used for partitioning (should be the aggregate id, e.g. orderId).
	Send(messageType kafka.MessageType, correlationId string, key string, payload any) error
	Close() error
}

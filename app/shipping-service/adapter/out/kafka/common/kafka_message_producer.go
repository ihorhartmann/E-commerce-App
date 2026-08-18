package common

import (
	"shippingservice/adapter/common/kafka"
)

//go:generate go run go.uber.org/mock/mockgen -source=kafka_message_producer.go -destination=mocks/mock_kafka_message_producer.go -package=kafkamocks

type KafkaMessageProducer interface {
	// Send publishes payload for messageType, with correlationId carried in the message headers
	// for tracing and key used for partitioning (should be the aggregate id, e.g. orderId).
	Send(messageType kafka.MessageType, correlationId string, key string, payload any) error
	Close() error
}

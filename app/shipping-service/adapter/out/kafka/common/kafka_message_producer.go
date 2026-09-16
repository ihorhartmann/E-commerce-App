package common

import (
	"shippingservice/application/port/out/outbox/dto"
)

//go:generate go run go.uber.org/mock/mockgen -source=kafka_message_producer.go -destination=mocks/mock_kafka_message_producer.go -package=kafkamocks

type KafkaMessageProducer interface {
	SendBatch(messages []*dto.OutboxMessage) error
	Close() error
}

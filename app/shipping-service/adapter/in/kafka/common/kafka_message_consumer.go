package common

import (
	"shippingservice/adapter/common/kafka"
)

type KafkaMessageConsumer interface {
	ConsumeAsync(func(messageType kafka.MessageType, messageId string, correlationId string, payload []byte) error)
	Close() error
}

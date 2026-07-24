package common

import (
	"inventoryservice/application/ports/common/kafka"
)

type KafkaMessageConsumer interface {
	ConsumeAsync(func(messageType kafka.MessageType, messageId string, correlationId string, payload []byte) error)
	Close() error
}

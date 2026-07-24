package common

import (
	"inventoryservice/application/ports/out/kafka/common"
)

type KafkaMessageConsumer interface {
	ConsumeAsync(func(messageType common.MessageType, messageId string, correlationId string, payload []byte) error)
	Close() error
}

package common

import (
	"inventoryservice/application/ports/common/kafka"
)

type KafkaMessageProducer interface {
	Send(messageType kafka.MessageType, correlationId string, key string, payload any) error
	Close() error
}

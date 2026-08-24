package common

import (
	"inventoryservice/application/port/common/kafka"
)

type KafkaMessageProducer interface {
	Send(messageType kafka.MessageType, correlationId string, key string, payload any) error
	Close() error
}

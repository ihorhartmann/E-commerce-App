package common

import (
	"context"

	"inventoryservice/application/port/common/kafka"
)

type KafkaMessageConsumer interface {
	ConsumeAsync(func(ctx context.Context, messageType kafka.MessageType, messageId string, correlationId string, payload []byte) error)
	Close() error
}

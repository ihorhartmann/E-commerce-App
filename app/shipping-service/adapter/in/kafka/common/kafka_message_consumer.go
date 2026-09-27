package common

import (
	"context"

	"shippingservice/adapter/common/kafka"

	"github.com/google/uuid"
)

type KafkaMessageConsumer interface {
	ConsumeAsync(func(ctx context.Context, messageType kafka.MessageType, messageId uuid.UUID, correlationId string, payload []byte) error)
	Close() error
}

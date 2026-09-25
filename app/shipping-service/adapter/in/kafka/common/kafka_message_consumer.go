package common

import (
	"context"

	"shippingservice/adapter/common/kafka"
)

type KafkaMessageConsumer interface {
	ConsumeAsync(func(ctx context.Context, messageType kafka.MessageType, messageId string, correlationId string, payload []byte) error)
	Close() error
}

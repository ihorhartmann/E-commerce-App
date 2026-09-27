package outbox

import (
	"context"
	"time"

	"shippingservice/application/port/out/outbox/dto"

	"github.com/google/uuid"
)

//go:generate go run go.uber.org/mock/mockgen -source=outbox_message_repository.go -destination=mocks/mock_outbox_message_repository.go -package=outboxmocks

type MessageRepository interface {
	Insert(ctx context.Context, message dto.OutboxMessage) error
	// FetchUnsent returns up to limit not-yet-sent messages, oldest first
	FetchUnsent(ctx context.Context, limit int) ([]*dto.OutboxMessage, error)
	// MarkSentBatch records that every message in messageIDs was published to Kafka at sentAt
	MarkSentBatch(ctx context.Context, messageIDs []uuid.UUID, sentAt time.Time) error
}

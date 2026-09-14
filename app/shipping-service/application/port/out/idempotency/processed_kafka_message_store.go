package idempotency

import (
	"context"
	"time"

	"shippingservice/application/port/out/idempotency/dto"
)

//go:generate go run go.uber.org/mock/mockgen -source=processed_kafka_message_store.go -destination=mocks/mock_processed_kafka_message_store.go -package=mocks

// ProcessedKafkaMessageStore records which Kafka messages have already been processed, so a
// redelivered message can be recognized and skipped instead of re-running its business logic.
type ProcessedKafkaMessageStore interface {
	// MarkProcessed records msg as processed. Must be called inside the same transaction as the
	// business mutation it protects. Returns alreadyProcessed=true if msg.MessageID was recorded
	// before, in which case the caller must not run the mutation again.
	MarkProcessed(ctx context.Context, msg dto.KafkaMessageMetadata) (alreadyProcessed bool, err error)
	// DeleteExpired deletes up to limit rows processed before cutoff, returning how many were
	// deleted. Called repeatedly by a sweep until it returns 0, to bound each individual delete.
	DeleteExpired(ctx context.Context, cutoff time.Time, limit int) (int64, error)
}

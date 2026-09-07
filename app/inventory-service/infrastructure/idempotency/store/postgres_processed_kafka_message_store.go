package store

import (
	"context"
	"inventoryservice/infrastructure/idempotency/store/row"
	"time"

	"inventoryservice/application/port/out/idempotency"
	"inventoryservice/application/port/out/idempotency/dto"
	"inventoryservice/infrastructure/gorm/transaction"

	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

type postgresProcessedKafkaMessageStore struct {
	db *gorm.DB
}

func NewPostgresProcessedKafkaMessageStore(db *gorm.DB) idempotency.ProcessedKafkaMessageStore {
	return &postgresProcessedKafkaMessageStore{db: db}
}

// MarkProcessed requires an active transaction in ctx (see transaction.From) - it must run in the
// same transaction as the business mutation it protects, never on its own. Uses INSERT ... ON
// CONFLICT DO NOTHING rather than catching a duplicate-key error: a redelivered messageID is a
// routine, expected outcome here, not an exceptional one, and this way Postgres never raises an
// error (and never aborts the transaction) for it - RowsAffected alone tells us which case we're in.
func (s *postgresProcessedKafkaMessageStore) MarkProcessed(ctx context.Context, msg dto.KafkaMessageMetadata) (bool, error) {
	tx, err := transaction.From(ctx)
	if err != nil {
		return false, err
	}

	processedRow := row.ProcessedKafkaMessageRow{
		ID:            msg.MessageID,
		CorrelationID: msg.CorrelationID,
		MessageType:   msg.MessageType,
		ProcessedAt:   time.Now(),
	}

	result := tx.WithContext(ctx).Clauses(clause.OnConflict{DoNothing: true}).Create(&processedRow)
	if result.Error != nil {
		return false, result.Error
	}

	return result.RowsAffected == 0, nil
}

func (s *postgresProcessedKafkaMessageStore) DeleteExpired(ctx context.Context, cutoff time.Time, limit int) (int64, error) {
	result := s.db.WithContext(ctx).Exec(
		`DELETE FROM processed_kafka_messages WHERE ctid IN (
			SELECT ctid FROM processed_kafka_messages WHERE processed_at < ? LIMIT ?
		)`, cutoff, limit)

	return result.RowsAffected, result.Error
}

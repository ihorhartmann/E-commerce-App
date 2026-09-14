package store

import (
	"context"
	"errors"
	"inventoryservice/infrastructure/idempotency/store/row"
	"time"

	"inventoryservice/application/port/out/idempotency"
	"inventoryservice/application/port/out/idempotency/dto"

	"gorm.io/gorm"
)

type postgresHTTPRequestStore struct {
	db *gorm.DB
}

func NewPostgresHTTPRequestStore(db *gorm.DB) idempotency.HTTPRequestStore {
	return &postgresHTTPRequestStore{db: db}
}

func (s *postgresHTTPRequestStore) Insert(ctx context.Context, idempotencyKey string, requestHash string) (bool, error) {
	now := time.Now()
	record := row.HTTPRequestRow{
		ID:          idempotencyKey,
		RequestHash: requestHash,
		Status:      row.InProgress,
		CreatedAt:   now,
		ClaimedAt:   now,
	}

	err := s.db.WithContext(ctx).Create(&record).Error
	if err == nil {
		return true, nil
	}
	if errors.Is(err, gorm.ErrDuplicatedKey) {
		return false, nil
	}

	return false, err
}

func (s *postgresHTTPRequestStore) Find(ctx context.Context, idempotencyKey string) (*dto.HTTPRequestRecord, error) {
	var existing row.HTTPRequestRow
	if err := s.db.WithContext(ctx).First(&existing, "id = ?", idempotencyKey).Error; err != nil {
		return nil, err
	}

	return row.ToRecord(&existing), nil
}

// Reclaim atomically claims idempotencyKey's row if it's still IN_PROGRESS and its last claim is
// older than ttl - the WHERE clause makes the claim itself race-safe against a concurrent retry
// doing the same thing.
func (s *postgresHTTPRequestStore) Reclaim(ctx context.Context, idempotencyKey string, ttl time.Duration) (bool, error) {
	result := s.db.WithContext(ctx).Model(&row.HTTPRequestRow{}).
		Where("id = ? AND status = ? AND claimed_at < ?", idempotencyKey, row.InProgress, time.Now().Add(-ttl)).
		Updates(map[string]any{"claimed_at": time.Now()})
	if result.Error != nil {
		return false, result.Error
	}

	return result.RowsAffected > 0, nil
}

func (s *postgresHTTPRequestStore) Complete(ctx context.Context, idempotencyKey string, httpCode int, response []byte) error {
	result := s.db.WithContext(ctx).Model(&row.HTTPRequestRow{}).
		Where("id = ?", idempotencyKey).
		Updates(map[string]any{
			"status":       row.Completed,
			"http_code":    httpCode,
			"response":     response,
			"completed_at": time.Now(),
		})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return idempotency.ErrRequestNotFound
	}

	return nil
}

func (s *postgresHTTPRequestStore) DeleteExpired(ctx context.Context, cutoff time.Time, limit int) (int64, error) {
	result := s.db.WithContext(ctx).Exec(
		`DELETE FROM idempotent_http_requests WHERE ctid IN (
			SELECT ctid FROM idempotent_http_requests WHERE created_at < ? LIMIT ?
		)`, cutoff, limit)

	return result.RowsAffected, result.Error
}

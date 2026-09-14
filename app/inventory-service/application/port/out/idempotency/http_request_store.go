package idempotency

import (
	"context"
	"time"

	"inventoryservice/application/port/out/idempotency/dto"
)

//go:generate go run go.uber.org/mock/mockgen -source=http_request_store.go -destination=mocks/mock_http_request_store.go -package=mocks

// HTTPRequestStore is a pure persistence port for idempotent_http_requests rows. It knows nothing
// about what a failed Insert or a stale record mean - that interpretation belongs to
// interfaces/http/idempotency.HTTPIdempotencyService, which composes this store. Every method here
// is a single, direct DB operation with no business branching of its own.
type HTTPRequestStore interface {
	// Insert tries to insert a new IN_PROGRESS row for idempotencyKey. Returns false (no error) if
	// idempotencyKey already exists.
	Insert(ctx context.Context, idempotencyKey string, requestHash string) (bool, error)
	// Find fetches the existing record for idempotencyKey.
	Find(ctx context.Context, idempotencyKey string) (*dto.HTTPRequestRecord, error)
	// Reclaim atomically claims idempotencyKey's row if it's still IN_PROGRESS and older than ttl,
	// so a concurrent retry doing the same thing can't both succeed. Returns false (no error) if
	// the row no longer matches that condition.
	Reclaim(ctx context.Context, idempotencyKey string, ttl time.Duration) (bool, error)
	// Complete records the final response for idempotencyKey, moving it to COMPLETED.
	Complete(ctx context.Context, idempotencyKey string, httpCode int, response []byte) error
	// DeleteExpired deletes up to limit rows created before cutoff, returning how many were
	// deleted. Called repeatedly by a sweep until it returns 0, to bound each individual delete.
	DeleteExpired(ctx context.Context, cutoff time.Time, limit int) (int64, error)
}

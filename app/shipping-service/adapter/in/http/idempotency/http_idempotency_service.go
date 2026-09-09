package idempotency

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"time"

	"shippingservice/adapter/in/http/idempotency/dto"
	"shippingservice/application/port/out/idempotency"
	storedto "shippingservice/application/port/out/idempotency/dto"
)

//go:generate go run go.uber.org/mock/mockgen -source=http_idempotency_service.go -destination=mocks/mock_http_idempotency_service.go -package=mocks

// HTTPIdempotencyService decides, per idempotency key, whether an HTTP request should proceed,
// replay a cached response, or be rejected, and records the final response once the caller has
// one. It holds all the HTTP idempotency-key decision logic.
type HTTPIdempotencyService interface {
	// Claim tries to start processing idempotencyKey for request. ttl is how long an IN_PROGRESS
	// record is honored before it's treated as abandoned (e.g. the server crashed mid-request) and
	// reclaimed.
	Claim(ctx context.Context, idempotencyKey string, request dto.RequestFingerprint, ttl time.Duration) (*dto.RequestAttempt, error)
	// Complete records the final response for idempotencyKey, moving it to COMPLETED.
	Complete(ctx context.Context, idempotencyKey string, httpCode int, response []byte) error
}

type httpIdempotencyService struct {
	store idempotency.HTTPRequestStore
}

func NewHTTPIdempotencyService(store idempotency.HTTPRequestStore) HTTPIdempotencyService {
	return &httpIdempotencyService{store: store}
}

func (s *httpIdempotencyService) Claim(
	ctx context.Context,
	idempotencyKey string,
	request dto.RequestFingerprint,
	ttl time.Duration,
) (*dto.RequestAttempt, error) {
	requestHash := s.hashRequest(request)

	inserted, err := s.store.Insert(ctx, idempotencyKey, requestHash)
	if err != nil {
		return nil, err
	}
	if inserted {
		return &dto.RequestAttempt{Result: dto.Claimed}, nil
	}

	record, err := s.store.Get(ctx, idempotencyKey)
	if err != nil {
		return nil, err
	}

	return s.resolveExistingClaim(ctx, record, requestHash, ttl)
}

// resolveExistingClaim decides what to do about idempotencyKey's already-existing record: reject
// it as a hash mismatch, replay a cached response, report it as in-progress, or - if the claim is
// stale - atomically reclaim it so the caller can proceed as if this were a brand-new key.
func (s *httpIdempotencyService) resolveExistingClaim(
	ctx context.Context,
	record *storedto.HTTPRequestRecord,
	requestHash string,
	ttl time.Duration,
) (*dto.RequestAttempt, error) {
	if record.RequestHash != requestHash {
		return &dto.RequestAttempt{Result: dto.HashMismatch, Record: record}, nil
	}
	if record.CompletedAt != nil {
		return &dto.RequestAttempt{Result: dto.Cached, Record: record}, nil
	}
	if record.ClaimedAt.After(time.Now().Add(-ttl)) {
		return &dto.RequestAttempt{Result: dto.InProgress, Record: record}, nil
	}

	reclaimed, err := s.store.Reclaim(ctx, record.ID, ttl)
	if err != nil {
		return nil, err
	}
	if !reclaimed {
		return &dto.RequestAttempt{Result: dto.InProgress, Record: record}, nil
	}

	return &dto.RequestAttempt{Result: dto.Claimed}, nil
}

func (s *httpIdempotencyService) Complete(ctx context.Context, idempotencyKey string, httpCode int, response []byte) error {
	return s.store.Complete(ctx, idempotencyKey, httpCode, response)
}

// hashRequest computes the request hash used to detect whether a retried Idempotency-Key was
// reused with a different request.
func (s *httpIdempotencyService) hashRequest(request dto.RequestFingerprint) string {
	sum := sha256.New()
	sum.Write([]byte(request.Method))
	sum.Write([]byte(request.Path))
	sum.Write(request.Body)
	return hex.EncodeToString(sum.Sum(nil))
}

package middleware

import "errors"

var (
	ErrMissingIdempotencyKey = errors.New("missing Idempotency-Key header")
	ErrIdempotencyKeyReused  = errors.New("Idempotency-Key was already used with a different request")
	ErrRequestInProgress     = errors.New("a request with this Idempotency-Key is already in progress")
)

package store

import "shippingservice/domain/apperror"

// The failures of the Postgres idempotency stores. They are not part of the idempotency ports: no
// caller tells them apart from any other internal failure.
var (
	ErrRequestNotFound = apperror.New("IDEMPOTENT_REQUEST_NOT_FOUND", apperror.CategoryNotFound, "Idempotent request not found")
	// ErrStoreFailure is what the stores report a database error with - its cause is the error gorm
	// returned.
	ErrStoreFailure = apperror.New("IDEMPOTENCY_STORE_FAILURE", apperror.CategoryInternal, "Internal server error")
)

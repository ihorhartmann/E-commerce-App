package dto

import (
	storedto "inventoryservice/application/port/out/idempotency/dto"
)

type RequestResult string

const (
	// Claimed means idempotencyKey was not seen before (or its prior attempt was abandoned) - the
	// caller should proceed and call Complete once it has a response.
	Claimed RequestResult = "CLAIMED"
	// Cached means idempotencyKey already completed with the same request hash - the caller
	// should return Record.HTTPCode/Record.Response as-is, without re-running anything.
	Cached RequestResult = "CACHED"
	// HashMismatch means idempotencyKey was reused with a different request body.
	HashMismatch RequestResult = "HASH_MISMATCH"
	// InProgress means idempotencyKey is currently being processed by another request.
	InProgress RequestResult = "IN_PROGRESS"
)

type RequestAttempt struct {
	Result RequestResult
	Record *storedto.HTTPRequestRecord
}

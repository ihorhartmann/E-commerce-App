package idempotency

import "errors"

var ErrRequestNotFound = errors.New("idempotent http request not found")

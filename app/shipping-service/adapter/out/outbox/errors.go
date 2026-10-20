package outbox

import "shippingservice/domain/apperror"

// The failures of the Postgres outbox. They are not part of the outbox port: no caller tells them
// apart from any other internal failure.
var (
	ErrOutboxMessageNotFound = apperror.New("OUTBOX_MESSAGE_NOT_FOUND", apperror.CategoryNotFound, "Outbox message not found")
	// ErrOutboxFailure is what the outbox reports a failure to store or read a message with - its
	// cause is the error gorm or the JSON encoder returned.
	ErrOutboxFailure = apperror.New("OUTBOX_FAILURE", apperror.CategoryInternal, "Internal server error")
)

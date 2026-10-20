package kafka

import "inventoryservice/domain/apperror"

// Whether a failed message is retried or dead-lettered at once is decided by the category of its
// error's sentinel: a permanent one (invalid, not found, conflict) can't change on a retry. These are
// the failures the consumer itself detects; every other one comes from the application.
var (
	ErrInvalidMessageHeaders = apperror.New("INVALID_MESSAGE_HEADERS", apperror.CategoryInvalid, "Invalid Kafka message headers")
	ErrMalformedMessage      = apperror.New("MALFORMED_MESSAGE", apperror.CategoryInvalid, "Malformed Kafka message payload")
)

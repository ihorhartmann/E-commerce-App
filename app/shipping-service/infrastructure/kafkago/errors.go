package kafkago

import "shippingservice/domain/apperror"

var (
	// ErrPublishFailure means kafka-go failed to write a message to the broker - its cause is the error
	// kafka-go returned. Internal, like a database failure: kafka-go doesn't tell an unreachable broker
	// from any other failure.
	ErrPublishFailure = apperror.New("KAFKA_PUBLISH_FAILURE", apperror.CategoryInternal, "Internal server error")
	// ErrDeadLetterPublishFailure means kafka-go failed to write a message to its dead-letter topic - its
	// cause is the error kafka-go returned. The consumer keeps retrying it, blocking the partition.
	ErrDeadLetterPublishFailure = apperror.New("DEAD_LETTER_PUBLISH_FAILURE", apperror.CategoryInternal, "Internal server error")
)

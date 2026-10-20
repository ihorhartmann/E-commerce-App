package kafkago

import "inventoryservice/domain/apperror"

var (
	// ErrPublishFailure means kafka-go failed to write a message to the broker - its cause is the error
	// kafka-go returned. Internal, like a database failure: kafka-go doesn't tell an unreachable broker
	// from any other failure.
	ErrPublishFailure = apperror.New("KAFKA_PUBLISH_FAILURE", apperror.CategoryInternal, "Internal server error")
	// ErrMessageBuildFailure means a message couldn't be built before sending it: its payload didn't
	// marshal, or its id couldn't be generated - a bug.
	ErrMessageBuildFailure = apperror.New("KAFKA_MESSAGE_BUILD_FAILURE", apperror.CategoryInternal, "Internal server error")
)

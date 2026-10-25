package kafkago

import "inventoryservice/domain/apperror"

// ErrDeadLetterPublishFailure means kafka-go failed to write a message to its dead-letter topic - its
// cause is the error kafka-go returned. The consumer keeps retrying it, blocking the partition.
var ErrDeadLetterPublishFailure = apperror.New("DEAD_LETTER_PUBLISH_FAILURE", apperror.CategoryInternal, "Internal server error")

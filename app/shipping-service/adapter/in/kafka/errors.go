package kafka

import (
	"errors"

	"shippingservice/domain"
)

// ErrNonRetryable marks a message-handling failure that repeats identically on every attempt (a
// malformed payload, a business rule rejecting the message, ...): the consumer sends such a
// message straight to its dead-letter topic instead of retrying it. Any failure that isn't marked
// with it is treated as transient and retried.
var ErrNonRetryable = errors.New("non-retryable failure")

type nonRetryableError struct {
	error
}

func (e *nonRetryableError) Unwrap() error {
	return e.error
}

func (e *nonRetryableError) Is(target error) bool {
	return target == ErrNonRetryable
}

// NonRetryable marks err as non-retryable, so errors.Is(err, ErrNonRetryable) reports true while
// errors.Is/As still see the original cause. It returns nil for a nil err.
func NonRetryable(err error) error {
	if err == nil {
		return nil
	}

	return &nonRetryableError{error: err}
}

var nonRetryableErrors = map[error]struct{}{
	domain.ErrInvalidInput: {},
}

// classify marks err as non-retryable if it, or an error it wraps, is one of nonRetryableErrors,
// and returns it unchanged otherwise (including nil).
func classify(err error) error {
	for e := err; e != nil; e = errors.Unwrap(e) {
		if _, ok := nonRetryableErrors[e]; ok {
			return NonRetryable(err)
		}
	}

	return err
}

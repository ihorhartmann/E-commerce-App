package kafka

import (
	"errors"
	"testing"
)

func TestNonRetryable(t *testing.T) {
	t.Run("marks the error as non-retryable", func(t *testing.T) {
		err := NonRetryable(errors.New("permanent failure"))

		if !errors.Is(err, ErrNonRetryable) {
			t.Errorf("errors.Is(err, ErrNonRetryable) = false, want true")
		}
	})

	t.Run("keeps the original cause reachable and its message unchanged", func(t *testing.T) {
		cause := errors.New("permanent failure")

		err := NonRetryable(cause)

		if !errors.Is(err, cause) {
			t.Errorf("errors.Is(err, cause) = false, want true")
		}
		if got, want := err.Error(), cause.Error(); got != want {
			t.Errorf("message = %q, want %q", got, want)
		}
	})

	t.Run("does not mark an unrelated error as non-retryable", func(t *testing.T) {
		err := errors.New("transient failure")

		if errors.Is(err, ErrNonRetryable) {
			t.Errorf("errors.Is(err, ErrNonRetryable) = true, want false")
		}
	})

	t.Run("returns nil for a nil error", func(t *testing.T) {
		if got := NonRetryable(nil); got != nil {
			t.Errorf("NonRetryable(nil) = %v, want nil", got)
		}
	})
}

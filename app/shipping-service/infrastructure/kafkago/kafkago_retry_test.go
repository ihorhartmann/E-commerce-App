package kafkago

import (
	"context"
	"errors"
	"fmt"
	"shippingservice/adapter/in/kafka"
	"slices"
	"testing"
	"time"
)

func TestBackoffDelays(t *testing.T) {
	t.Run("doubles the delay after every retry, starting from the initial one", func(t *testing.T) {
		got := backoffDelays(4, 10*time.Millisecond)

		want := []time.Duration{10 * time.Millisecond, 20 * time.Millisecond, 40 * time.Millisecond, 80 * time.Millisecond}
		if !slices.Equal(got, want) {
			t.Errorf("delays = %v, want %v", got, want)
		}
	})

	t.Run("gives the production policy of 5 attempts waiting 1s, 2s, 4s, 8s between them", func(t *testing.T) {
		got := backoffDelays(maxRetries, initialBackoff)

		want := []time.Duration{time.Second, 2 * time.Second, 4 * time.Second, 8 * time.Second}
		if !slices.Equal(got, want) {
			t.Errorf("delays = %v, want %v", got, want)
		}
	})
}

func TestRunWithRetry(t *testing.T) {
	t.Run("returns nil after a single attempt when op succeeds, without waiting", func(t *testing.T) {
		// An hour-long delay, but a context that gives up after 2s: waiting after a success would
		// surface as a context error instead of hanging the test.
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		gotCalls := 0

		err := runWithRetry(ctx, []time.Duration{time.Hour}, func() error {
			gotCalls++
			return nil
		})

		if err != nil {
			t.Errorf("err = %v, want nil", err)
		}
		if wantCalls := 1; gotCalls != wantCalls {
			t.Errorf("calls = %d, want %d", gotCalls, wantCalls)
		}
	})

	t.Run("retries a failing op until it succeeds", func(t *testing.T) {
		failures := 2
		gotCalls := 0

		err := runWithRetry(context.Background(), backoffDelays(4, time.Millisecond), func() error {
			gotCalls++
			if gotCalls <= failures {
				return errors.New("transient failure")
			}
			return nil
		})

		if err != nil {
			t.Errorf("err = %v, want nil", err)
		}
		if wantCalls := failures + 1; gotCalls != wantCalls {
			t.Errorf("calls = %d, want %d", gotCalls, wantCalls)
		}
	})

	t.Run("gives up after len(delays)+1 attempts and returns the last error", func(t *testing.T) {
		delays := backoffDelays(4, time.Millisecond)
		gotCalls := 0

		err := runWithRetry(context.Background(), delays, func() error {
			gotCalls++
			return fmt.Errorf("failure %d", gotCalls)
		})

		wantCalls := len(delays) + 1
		if gotCalls != wantCalls {
			t.Errorf("calls = %d, want %d", gotCalls, wantCalls)
		}
		if wantErr := fmt.Sprintf("failure %d", wantCalls); err == nil || err.Error() != wantErr {
			t.Errorf("err = %v, want %q", err, wantErr)
		}
	})

	t.Run("does not retry a non-retryable error", func(t *testing.T) {
		gotCalls := 0

		err := runWithRetry(context.Background(), backoffDelays(4, time.Millisecond), func() error {
			gotCalls++
			return kafka.NonRetryable(errors.New("permanent failure"))
		})

		if !errors.Is(err, kafka.ErrNonRetryable) {
			t.Errorf("err = %v, want a non-retryable error", err)
		}
		if wantCalls := 1; gotCalls != wantCalls {
			t.Errorf("calls = %d, want %d", gotCalls, wantCalls)
		}
	})

	t.Run("waits the configured delay between attempts", func(t *testing.T) {
		delay := 20 * time.Millisecond
		delays := []time.Duration{delay, delay}
		start := time.Now()

		_ = runWithRetry(context.Background(), delays, func() error { return errors.New("transient failure") })

		if elapsed, wantAtLeast := time.Since(start), 2*delay; elapsed < wantAtLeast {
			t.Errorf("elapsed = %v, want at least %v", elapsed, wantAtLeast)
		}
	})

	t.Run("stops waiting and returns the context's error when the context is cancelled between attempts", func(t *testing.T) {
		ctx, cancel := context.WithCancel(context.Background())
		gotCalls := 0

		err := runWithRetry(ctx, []time.Duration{time.Hour}, func() error {
			gotCalls++
			cancel()
			return errors.New("transient failure")
		})

		if !errors.Is(err, context.Canceled) {
			t.Errorf("err = %v, want %v", err, context.Canceled)
		}
		if wantCalls := 1; gotCalls != wantCalls {
			t.Errorf("calls = %d, want %d", gotCalls, wantCalls)
		}
	})
}

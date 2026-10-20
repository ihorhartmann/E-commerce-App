package kafkago

import (
	"context"
	"time"

	"shippingservice/domain/apperror"
)

const (
	// A failing message is attempted maxRetries+1 = 5 times in total, waiting 1s, 2s, 4s, 8s (15s in
	// total) between the attempts, before it is dead-lettered. All services use the same numbers.
	maxRetries        = 4
	initialBackoff    = time.Second
	backoffMultiplier = 2
)

func backoffDelays(retries int, initial time.Duration) []time.Duration {
	delays := make([]time.Duration, retries)
	delay := initial
	for i := range delays {
		delays[i] = delay
		delay *= backoffMultiplier
	}

	return delays
}

// runWithRetry runs action until it succeeds, fails permanently - the category of its error's
// sentinel says retrying can't change the outcome - or runs out of delays. An error with no sentinel
// counts as internal, so it is retried.
func runWithRetry(ctx context.Context, delays []time.Duration, action func() error) error {
	for attempt := 0; ; attempt++ {
		err := action()
		if err == nil || apperror.OutermostError(err).Category().Permanent() || attempt == len(delays) {
			return err
		}

		if err = sleep(ctx, delays[attempt]); err != nil {
			return err
		}
	}
}

// sleep waits for delay, or returns ctx's error as soon as ctx is done.
func sleep(ctx context.Context, delay time.Duration) error {
	timer := time.NewTimer(delay)
	defer timer.Stop()

	select {
	case <-timer.C:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

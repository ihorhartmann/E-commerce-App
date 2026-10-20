package scheduled

import (
	"context"
	"log/slog"
	"time"

	"inventoryservice/application/port/out/idempotency"
	"inventoryservice/domain/apperror"
)

const (
	httpRequestSweepInterval  = 5 * time.Minute
	httpRequestRetention      = 24 * time.Hour
	httpRequestSweepBatchSize = 1000
)

// HTTPRequestSweep periodically deletes expired idempotent_http_requests rows in small batches.
type HTTPRequestSweep struct {
	store idempotency.HTTPRequestStore
}

func NewHTTPRequestSweep(store idempotency.HTTPRequestStore) *HTTPRequestSweep {
	return &HTTPRequestSweep{store: store}
}

// StartAsync runs the sweep on a ticker in its own goroutine, returning immediately. The goroutine
// exits once ctx is done.
func (s *HTTPRequestSweep) StartAsync(ctx context.Context) {
	go func() {
		ticker := time.NewTicker(httpRequestSweepInterval)
		defer ticker.Stop()

		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				s.sweep(ctx)
			}
		}
	}()
}

// sweep recovers a panic and logs it: an unrecovered panic in this goroutine would stop the whole
// service, and the next tick just tries again.
func (s *HTTPRequestSweep) sweep(ctx context.Context) {
	defer func() {
		if recovered := recover(); recovered != nil {
			slog.Error("idempotent_http_requests sweep panicked", apperror.LogAttr(apperror.FromPanic(recovered)))
		}
	}()

	cutoff := time.Now().Add(-httpRequestRetention)
	for {
		deleted, err := s.store.DeleteExpired(ctx, cutoff, httpRequestSweepBatchSize)
		if err != nil {
			slog.Error("idempotent_http_requests sweep failed", apperror.LogAttr(err))
			return
		}
		if deleted == 0 {
			return
		}
	}
}

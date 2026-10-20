package scheduled

import (
	"context"
	"log/slog"
	"time"

	"inventoryservice/application/port/out/idempotency"
	"inventoryservice/domain/apperror"
)

const (
	processedMessageSweepInterval  = 5 * time.Minute
	processedMessageRetention      = 30 * 24 * time.Hour
	processedMessageSweepBatchSize = 1000
)

// ProcessedKafkaMessageSweep periodically deletes expired processed_kafka_messages rows in small
// batches.
type ProcessedKafkaMessageSweep struct {
	store idempotency.ProcessedKafkaMessageStore
}

func NewProcessedKafkaMessageSweep(store idempotency.ProcessedKafkaMessageStore) *ProcessedKafkaMessageSweep {
	return &ProcessedKafkaMessageSweep{store: store}
}

// StartAsync runs the sweep on a ticker in its own goroutine, returning immediately. The goroutine
// exits once ctx is done.
func (s *ProcessedKafkaMessageSweep) StartAsync(ctx context.Context) {
	go func() {
		ticker := time.NewTicker(processedMessageSweepInterval)
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
func (s *ProcessedKafkaMessageSweep) sweep(ctx context.Context) {
	defer func() {
		if recovered := recover(); recovered != nil {
			slog.Error("processed_kafka_messages sweep panicked", apperror.LogAttr(apperror.FromPanic(recovered)))
		}
	}()

	cutoff := time.Now().Add(-processedMessageRetention)
	for {
		deleted, err := s.store.DeleteExpired(ctx, cutoff, processedMessageSweepBatchSize)
		if err != nil {
			slog.Error("processed_kafka_messages sweep failed", apperror.LogAttr(err))
			return
		}
		if deleted == 0 {
			return
		}
	}
}

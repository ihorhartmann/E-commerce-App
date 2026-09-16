package scheduled

import (
	"context"
	"log/slog"
	"time"

	kafkacommon "shippingservice/adapter/out/kafka/common"
	"shippingservice/application/port/out/outbox"
)

const (
	outboxRelayInterval  = 2 * time.Second
	outboxRelayBatchSize = 100
)

// OutboxMessageRelay periodically publishes not-yet-sent outbox_messages rows to Kafka through the
// plain KafkaMessageProducer as a single batch, and marks the whole batch sent once it publishes
// successfully.
type OutboxMessageRelay struct {
	repository outbox.MessageRepository
	producer   kafkacommon.KafkaMessageProducer
}

func NewOutboxMessageRelay(repository outbox.MessageRepository, producer kafkacommon.KafkaMessageProducer) *OutboxMessageRelay {
	return &OutboxMessageRelay{repository: repository, producer: producer}
}

func (r *OutboxMessageRelay) StartAsync(ctx context.Context) {
	go func() {
		ticker := time.NewTicker(outboxRelayInterval)
		defer ticker.Stop()

		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				r.relay(ctx)
			}
		}
	}()
}

func (r *OutboxMessageRelay) relay(ctx context.Context) {
	messages, err := r.repository.FetchUnsent(ctx, outboxRelayBatchSize)
	if err != nil {
		slog.Error("outbox relay failed to fetch unsent messages", "error", err)
		return
	}
	if len(messages) == 0 {
		return
	}

	if err = r.producer.SendBatch(messages); err != nil {
		slog.Error("outbox relay failed to publish message batch", "count", len(messages), "error", err)
		return
	}

	messageIDs := make([]string, len(messages))
	for i, message := range messages {
		messageIDs[i] = message.MessageID
	}

	if err = r.repository.MarkSentBatch(ctx, messageIDs, time.Now()); err != nil {
		slog.Error("outbox relay failed to mark message batch sent", "count", len(messageIDs), "error", err)
	}
}

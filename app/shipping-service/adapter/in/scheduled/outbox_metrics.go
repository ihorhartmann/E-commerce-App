package scheduled

import (
	"context"

	"shippingservice/application/port/out/outbox/dto"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

// The step of a relay run that failed.
const (
	relayStageFetch   = "fetch"
	relayStagePublish = "publish"
	relayStageMark    = "mark"
)

// The meter is the global provider's (see infrastructure/telemetry) - until one is installed, e.g. in tests, a no-op.
// How many messages wait in the outbox, and for how long, is read from the database itself (Alloy's postgres
// exporter), once for all replicas.
var (
	outboxMeter      = otel.Meter("shippingservice/adapter/in/scheduled")
	relayFailures, _ = outboxMeter.Int64Counter("ecommerce.outbox.relay.failures",
		metric.WithDescription("Failed outbox relay runs, by the step that failed"),
		metric.WithUnit("{failure}"))
	publishedMessages, _ = outboxMeter.Int64Counter("ecommerce.outbox.messages.published",
		metric.WithDescription("Outbox messages published to Kafka, by message type - the business events this service emits"),
		metric.WithUnit("{message}"))
)

func recordRelayFailureMetric(ctx context.Context, stage string) {
	relayFailures.Add(ctx, 1, metric.WithAttributes(attribute.String("stage", stage)))
}

func recordPublishedMetric(ctx context.Context, messages []*dto.OutboxMessage) {
	for _, message := range messages {
		publishedMessages.Add(ctx, 1, metric.WithAttributes(attribute.String("message.type", message.MessageType)))
	}
}

package kafkago

import (
	"context"
	"time"

	"shippingservice/adapter/common/kafka"
	"shippingservice/domain/apperror"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

// The outcome of one attempt at handling a message: handled, failed in a way a retry can fix, or rejected - failed for
// good, dead-lettered without a retry.
const (
	outcomeSuccess  = "success"
	outcomeFailed   = "failed"
	outcomeRejected = "rejected"
	// unknownMessageType names a message without a messageType header
	unknownMessageType = "unknown"
)

// durationBuckets are the bucket boundaries, in seconds, OpenTelemetry's semantic conventions give request durations
// - the same in every service, so their percentiles compare.
var durationBuckets = []float64{0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10}

// meter is the global provider's (see infrastructure/telemetry) - until one is installed, e.g. in tests, a no-op.
var meter = otel.Meter("shippingservice/infrastructure/kafkago")

var (
	messageDuration, _ = meter.Float64Histogram("ecommerce.kafka.message.duration",
		metric.WithDescription("Duration of one attempt at handling a Kafka message, by its outcome"),
		metric.WithUnit("s"),
		metric.WithExplicitBucketBoundaries(durationBuckets...))
	deadLetteredMessages, _ = meter.Int64Counter("ecommerce.kafka.message.dead_lettered",
		metric.WithDescription("Kafka messages published to their dead-letter topic, each waiting for a manual redrive"),
		metric.WithUnit("{message}"))
	failures, _ = meter.Int64Counter("ecommerce.errors",
		metric.WithDescription("Failed HTTP requests and Kafka message attempts, by error code"),
		metric.WithUnit("{error}"))
)

// recordAttemptMetric records one attempt at handling a message of messageType from topic that started at start and ended
// with err, nil for a success. A failure is counted by its code too.
func recordAttemptMetric(ctx context.Context, topic string, messageType kafka.MessageType, start time.Time, err error) {
	outcome := outcomeSuccess
	if err != nil {
		foundErr := apperror.OutermostError(err)
		outcome = outcomeFailed
		if foundErr.Category().Permanent() {
			outcome = outcomeRejected
		}
		failures.Add(ctx, 1, metric.WithAttributes(
			attribute.String("error.code", foundErr.Code()),
			attribute.String("transport", "kafka"),
		))
	}

	messageDuration.Record(ctx, time.Since(start).Seconds(), metric.WithAttributes(
		attribute.String("topic", topic),
		attribute.String("message.type", messageTypeOrUnknown(messageType)),
		attribute.String("outcome", outcome),
	))
}

// recordDeadLetteredMetric records that a message of messageType from topic was published to its dead-letter topic.
func recordDeadLetteredMetric(ctx context.Context, topic string, messageType kafka.MessageType) {
	deadLetteredMessages.Add(ctx, 1, metric.WithAttributes(
		attribute.String("topic", topic),
		attribute.String("message.type", messageTypeOrUnknown(messageType)),
	))
}

func messageTypeOrUnknown(messageType kafka.MessageType) string {
	if messageType == "" {
		return unknownMessageType
	}
	return messageType.String()
}

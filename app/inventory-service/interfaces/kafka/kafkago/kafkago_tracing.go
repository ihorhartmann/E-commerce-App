package kafkago

import (
	"context"
	"inventoryservice/application/port/common/kafka"
	"strconv"

	"github.com/google/uuid"
	kafkago "github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"
)

// tracer is the global provider's (see infrastructure/telemetry) - until one is installed, e.g. in tests, a no-op.
var tracer = otel.Tracer("inventoryservice/interfaces/kafka/kafkago")

// startProcessSpan starts the consumer span message is handled in: a child of the span that sent it, as named by the
// message's traceparent header, or - without one - the root of a new trace.
func startProcessSpan(ctx context.Context, message kafkago.Message, groupID string) (context.Context, trace.Span) {
	ctx = otel.GetTextMapPropagator().Extract(ctx, newHeaderCarrier(&message.Headers))

	return tracer.Start(ctx, "process "+message.Topic,
		trace.WithSpanKind(trace.SpanKindConsumer),
		trace.WithAttributes(
			attribute.String("messaging.system", "kafka"),
			attribute.String("messaging.operation.type", "process"),
			attribute.String("messaging.destination.name", message.Topic),
			attribute.String("messaging.destination.partition.id", strconv.Itoa(message.Partition)),
			attribute.Int64("messaging.kafka.offset", message.Offset),
			attribute.String("messaging.consumer.group.name", groupID),
		),
	)
}

// setMessageAttributes adds what the message's headers say about it, once they're known to be valid.
func setMessageAttributes(span trace.Span, messageType kafka.MessageType, messageID uuid.UUID, correlationID string) {
	span.SetAttributes(
		attribute.String("messaging.message.type", messageType.String()),
		attribute.String("messaging.message.id", messageID.String()),
		attribute.String("messaging.message.conversation_id", correlationID),
	)
}

// markDeadLettered records on span that its message failed for good, with cause.
func markDeadLettered(span trace.Span, cause error) {
	span.RecordError(cause)
	span.SetStatus(codes.Error, "message dead-lettered")
}

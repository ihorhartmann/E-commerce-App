package kafkago

import (
	"context"
	"shippingservice/adapter/common/kafka"
	"strconv"

	"github.com/google/uuid"
	kafkago "github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/trace"
)

// traceparentKey is the W3C trace context header, as an outbox message stores it.
const traceparentKey = "traceparent"

// tracer is the global provider's (see infrastructure/telemetry) - until one is installed, e.g. in tests, a no-op.
var tracer = otel.Tracer("shippingservice/infrastructure/kafkago")

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

// startSendSpan starts the producer span message is sent in, as a child of the span traceparent names - the one the
// message was written to the outbox in - and writes its own trace context into message's headers, so the consumer
// continues the trace. Without a traceparent it starts nothing and returns nil: the consumer then starts a trace of
// its own.
func startSendSpan(topic, traceparent string, message *kafkago.Message, messageType, messageID, correlationID string) trace.Span {
	if traceparent == "" {
		return nil
	}

	ctx := otel.GetTextMapPropagator().Extract(context.Background(), propagation.MapCarrier{traceparentKey: traceparent})
	ctx, span := tracer.Start(ctx, "send "+topic,
		trace.WithSpanKind(trace.SpanKindProducer),
		trace.WithAttributes(
			attribute.String("messaging.system", "kafka"),
			attribute.String("messaging.operation.type", "send"),
			attribute.String("messaging.destination.name", topic),
			attribute.String("messaging.message.type", messageType),
			attribute.String("messaging.message.id", messageID),
			attribute.String("messaging.message.conversation_id", correlationID),
		),
	)
	otel.GetTextMapPropagator().Inject(ctx, newHeaderCarrier(&message.Headers))

	return span
}

// endSendSpans ends the producer spans of one batch - the nil ones are of messages sent without a trace - all marked
// failed with err when the batch failed to publish.
func endSendSpans(spans []trace.Span, err error) {
	for _, span := range spans {
		if span == nil {
			continue
		}
		if err != nil {
			span.RecordError(err)
			span.SetStatus(codes.Error, "message batch not published")
		}
		span.End()
	}
}

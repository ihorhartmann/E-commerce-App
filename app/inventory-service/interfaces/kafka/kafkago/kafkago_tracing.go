package kafkago

import (
	"context"
	"inventoryservice/application/port/common/kafka"
	"strconv"

	kafkago "github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"
)

const processOperation = "process"

// tracer is the global provider's (see infrastructure/telemetry) - until one is installed, e.g. in tests, a no-op.
var tracer = otel.Tracer("inventoryservice/interfaces/kafka/kafkago")

// startProcessSpan starts the consumer span message is handled in: a child of the span that sent it, as named by the
// message's traceparent header, or - without one - the root of a new trace. It is named and tagged from the headers as
// they arrived, before they are validated, so a message rejected for its headers still shows what it carried.
func startProcessSpan(ctx context.Context, message kafkago.Message, groupID string) (context.Context, trace.Span) {
	ctx = otel.GetTextMapPropagator().Extract(ctx, newHeaderCarrier(&message.Headers))

	messageType, _ := headerValue(message.Headers, kafka.MessageTypeHeader)
	attributes := append([]attribute.KeyValue{
		attribute.String("messaging.system", "kafka"),
		attribute.String("messaging.operation.type", processOperation),
		attribute.String("messaging.destination.name", message.Topic),
		attribute.String("messaging.destination.partition.id", strconv.Itoa(message.Partition)),
		attribute.Int64("messaging.kafka.offset", message.Offset),
		attribute.String("messaging.consumer.group.name", groupID),
	}, messageAttributes(message.Headers)...)

	return tracer.Start(ctx, messagingSpanName(processOperation, message.Topic, messageType),
		trace.WithSpanKind(trace.SpanKindConsumer),
		trace.WithAttributes(attributes...),
	)
}

// messagingSpanName is `{operation} {topic} {messageType}`: OpenTelemetry's messaging span name with the message type
// added at the end - as it arrived, even an unknown one; without one, just `{operation} {topic}`.
func messagingSpanName(operation, topic, messageType string) string {
	if messageType == "" {
		return operation + " " + topic
	}
	return operation + " " + topic + " " + messageType
}

// messageAttributes are what a message's headers say about it - its type, id and the saga's correlation id - as they
// arrived, even invalid ones, so a bad message can be traced to wherever it came from. A missing header adds nothing.
func messageAttributes(headers []kafkago.Header) []attribute.KeyValue {
	var attributes []attribute.KeyValue
	for key, attributeKey := range map[string]string{
		kafka.MessageTypeHeader:   "messaging.message.type",
		kafka.MessageIDHeader:     "messaging.message.id",
		kafka.CorrelationIDHeader: "messaging.message.conversation_id",
	} {
		if value, ok := headerValue(headers, key); ok {
			attributes = append(attributes, attribute.String(attributeKey, value))
		}
	}
	return attributes
}

// headerValue returns the value of the last header named key, as it arrived, and whether there is one.
func headerValue(headers []kafkago.Header, key string) (string, bool) {
	value, found := "", false
	for _, header := range headers {
		if header.Key == key {
			value, found = string(header.Value), true
		}
	}
	return value, found
}

// markDeadLettered records on span that its message failed for good, with cause.
func markDeadLettered(span trace.Span, cause error) {
	span.RecordError(cause)
	span.SetStatus(codes.Error, "message dead-lettered")
}

package kafkago

import (
	"context"
	"errors"
	"shippingservice/adapter/common/kafka"
	"sync"
	"testing"

	kafkago "github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/propagation"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	"go.opentelemetry.io/otel/trace"
)

const traceparentHeader = "traceparent"

var (
	installSpanRecorder sync.Once
	spanRecorder        *tracetest.SpanRecorder
)

// recordSpans installs, once per test binary, a global tracer provider that records every span ended - the global
// tracer delegates to the first provider installed only. Tests tell their spans apart by trace id.
func recordSpans() *tracetest.SpanRecorder {
	installSpanRecorder.Do(func() {
		spanRecorder = tracetest.NewSpanRecorder()
		otel.SetTracerProvider(sdktrace.NewTracerProvider(sdktrace.WithSpanProcessor(spanRecorder)))
		otel.SetTextMapPropagator(propagation.TraceContext{})
	})
	return spanRecorder
}

// endedSpanOfTrace returns the one span of the trace traceID that recorder saw end.
func endedSpanOfTrace(t *testing.T, recorder *tracetest.SpanRecorder, traceID string) sdktrace.ReadOnlySpan {
	t.Helper()

	var spans []sdktrace.ReadOnlySpan
	for _, span := range recorder.Ended() {
		if span.SpanContext().TraceID().String() == traceID {
			spans = append(spans, span)
		}
	}
	if len(spans) != 1 {
		t.Fatalf("ended spans of trace %s = %d, want 1", traceID, len(spans))
	}
	return spans[0]
}

func TestStartSendSpan(t *testing.T) {
	t.Run("starts a producer span as a child of the traceparent and writes its own into the headers", func(t *testing.T) {
		recorder := recordSpans()
		traceID := "6bf92f3577b34da6a3ce929d0e0e4736"
		parentSpanID := "00f067aa0ba902b7"
		topic := "shipping.events"
		messageType := "ShipmentDelivered"
		message := kafkago.Message{}

		span := startSendSpan(topic, "00-"+traceID+"-"+parentSpanID+"-01", &message, messageType, "0192f1d2-7c4b-7000-8000-000000000001", "corr-1")
		endSendSpans([]trace.Span{span}, nil)

		endedSpan := endedSpanOfTrace(t, recorder, traceID)
		if wantName := "send " + topic + " " + messageType; endedSpan.Name() != wantName {
			t.Errorf("span name = %q, want %q", endedSpan.Name(), wantName)
		}
		if gotParentSpanID := endedSpan.Parent().SpanID().String(); gotParentSpanID != parentSpanID {
			t.Errorf("parent span id = %q, want %q", gotParentSpanID, parentSpanID)
		}
		if wantKind := trace.SpanKindProducer; endedSpan.SpanKind() != wantKind {
			t.Errorf("span kind = %v, want %v", endedSpan.SpanKind(), wantKind)
		}
		wantTraceparent := "00-" + traceID + "-" + endedSpan.SpanContext().SpanID().String() + "-01"
		if gotTraceparent := newHeaderCarrier(&message.Headers).Get(traceparentHeader); gotTraceparent != wantTraceparent {
			t.Errorf("traceparent header = %q, want %q", gotTraceparent, wantTraceparent)
		}
	})

	t.Run("starts nothing and leaves the headers alone without a traceparent", func(t *testing.T) {
		message := kafkago.Message{}

		span := startSendSpan("shipping.events", "", &message, "ShipmentDelivered", "0192f1d2-7c4b-7000-8000-000000000001", "corr-2")

		if span != nil {
			t.Error("span != nil, want nil")
		}
		if len(message.Headers) != 0 {
			t.Errorf("headers = %v, want none", message.Headers)
		}
	})
}

func TestEndSendSpans(t *testing.T) {
	t.Run("marks every span of a batch that failed to publish as failed, skipping messages sent without a trace", func(t *testing.T) {
		recorder := recordSpans()
		traceID := "7bf92f3577b34da6a3ce929d0e0e4736"
		span := startSendSpan("shipping.events", "00-"+traceID+"-00f067aa0ba902b7-01", &kafkago.Message{}, "ShipmentDelivered", "0192f1d2-7c4b-7000-8000-000000000002", "corr-3")

		endSendSpans([]trace.Span{span, nil}, errors.New("broker down"))

		if wantCode := codes.Error; endedSpanOfTrace(t, recorder, traceID).Status().Code != wantCode {
			t.Errorf("span status = %v, want %v", endedSpanOfTrace(t, recorder, traceID).Status().Code, wantCode)
		}
	})
}

// attributeValue returns span's attribute key as a string, and whether it has one.
func attributeValue(span sdktrace.ReadOnlySpan, key string) (string, bool) {
	for _, keyValue := range span.Attributes() {
		if string(keyValue.Key) == key {
			return keyValue.Value.String(), true
		}
	}
	return "", false
}

func TestStartProcessSpan(t *testing.T) {
	t.Run("starts a consumer span as a child of the span the traceparent header names", func(t *testing.T) {
		recorder := recordSpans()
		traceID := "8bf92f3577b34da6a3ce929d0e0e4736"
		parentSpanID := "00f067aa0ba902b7"
		commandsTopic := "commands"
		groupID := "test-group"
		message := kafkago.Message{
			Topic:     commandsTopic,
			Partition: 2,
			Offset:    17,
			Headers:   []kafkago.Header{{Key: traceparentHeader, Value: []byte("00-" + traceID + "-" + parentSpanID + "-01")}},
		}

		ctx, span := startProcessSpan(context.Background(), message, groupID)
		span.End()

		if gotTraceID := trace.SpanContextFromContext(ctx).TraceID().String(); gotTraceID != traceID {
			t.Errorf("trace id of the returned context = %q, want %q", gotTraceID, traceID)
		}
		endedSpan := endedSpanOfTrace(t, recorder, traceID)
		if gotParentSpanID := endedSpan.Parent().SpanID().String(); gotParentSpanID != parentSpanID {
			t.Errorf("parent span id = %q, want %q", gotParentSpanID, parentSpanID)
		}
		if wantKind := trace.SpanKindConsumer; endedSpan.SpanKind() != wantKind {
			t.Errorf("span kind = %v, want %v", endedSpan.SpanKind(), wantKind)
		}
		if wantName := "process " + commandsTopic; endedSpan.Name() != wantName {
			t.Errorf("span name = %q, want %q", endedSpan.Name(), wantName)
		}
		wantAttributes := map[string]string{
			"messaging.system":                   "kafka",
			"messaging.operation.type":           "process",
			"messaging.destination.name":         commandsTopic,
			"messaging.destination.partition.id": "2",
			"messaging.kafka.offset":             "17",
			"messaging.consumer.group.name":      groupID,
		}
		for key, want := range wantAttributes {
			if got, ok := attributeValue(endedSpan, key); !ok || got != want {
				t.Errorf("attribute %s = %q (present: %v), want %q", key, got, ok, want)
			}
		}
	})

	t.Run("starts the root of a new trace when the message has no traceparent header", func(t *testing.T) {
		recorder := recordSpans()

		_, span := startProcessSpan(context.Background(), kafkago.Message{Topic: "commands"}, "test-group")
		span.End()

		endedSpan := endedSpanOfTrace(t, recorder, span.SpanContext().TraceID().String())
		if endedSpan.Parent().IsValid() {
			t.Errorf("parent = %v, want none", endedSpan.Parent())
		}
	})
}

func TestMarkDeadLettered(t *testing.T) {
	t.Run("marks the span failed and records the cause on it", func(t *testing.T) {
		recorder := recordSpans()
		cause := errors.New("permanent failure")
		_, span := otel.Tracer("test").Start(context.Background(), "process commands")

		markDeadLettered(span, cause)
		span.End()

		endedSpan := endedSpanOfTrace(t, recorder, span.SpanContext().TraceID().String())
		if wantCode := codes.Error; endedSpan.Status().Code != wantCode {
			t.Errorf("span status = %v, want %v", endedSpan.Status().Code, wantCode)
		}
		if wantEvents := 1; len(endedSpan.Events()) != wantEvents {
			t.Fatalf("span events = %d, want %d", len(endedSpan.Events()), wantEvents)
		}
		event := endedSpan.Events()[0]
		if wantName := "exception"; event.Name != wantName {
			t.Errorf("event name = %q, want %q", event.Name, wantName)
		}
		var gotMessage string
		for _, keyValue := range event.Attributes {
			if keyValue.Key == "exception.message" {
				gotMessage = keyValue.Value.AsString()
			}
		}
		if gotMessage != cause.Error() {
			t.Errorf("exception.message = %q, want %q", gotMessage, cause.Error())
		}
	})
}

func TestStartProcessSpanFromRawHeaders(t *testing.T) {
	t.Run("names and tags the span from the headers as they arrived, even invalid ones", func(t *testing.T) {
		recorder := recordSpans()
		topic := "commands"
		messageType := "SomethingUnknown"
		messageID := "not-a-uuid"
		correlationID := "corr-raw"
		message := kafkago.Message{
			Topic: topic,
			Headers: []kafkago.Header{
				{Key: kafka.MessageTypeHeader, Value: []byte(messageType)},
				{Key: kafka.MessageIDHeader, Value: []byte(messageID)},
				{Key: kafka.CorrelationIDHeader, Value: []byte(correlationID)},
			},
		}

		_, span := startProcessSpan(context.Background(), message, "test-group")
		span.End()

		endedSpan := endedSpanOfTrace(t, recorder, span.SpanContext().TraceID().String())
		if wantName := "process " + topic + " " + messageType; endedSpan.Name() != wantName {
			t.Errorf("span name = %q, want %q", endedSpan.Name(), wantName)
		}
		wantAttributes := map[string]string{
			"messaging.message.type":            messageType,
			"messaging.message.id":              messageID,
			"messaging.message.conversation_id": correlationID,
		}
		for key, want := range wantAttributes {
			if got, ok := attributeValue(endedSpan, key); !ok || got != want {
				t.Errorf("attribute %s = %q (present: %v), want %q", key, got, ok, want)
			}
		}
	})

	t.Run("leaves out the attribute of a header the message doesn't carry", func(t *testing.T) {
		recorder := recordSpans()
		topic := "commands"

		_, span := startProcessSpan(context.Background(), kafkago.Message{Topic: topic}, "test-group")
		span.End()

		endedSpan := endedSpanOfTrace(t, recorder, span.SpanContext().TraceID().String())
		if wantName := "process " + topic; endedSpan.Name() != wantName {
			t.Errorf("span name = %q, want %q", endedSpan.Name(), wantName)
		}
		for _, key := range []string{"messaging.message.type", "messaging.message.id", "messaging.message.conversation_id"} {
			if got, ok := attributeValue(endedSpan, key); ok {
				t.Errorf("attribute %s = %q, want none", key, got)
			}
		}
	})
}

func TestHeaderValue(t *testing.T) {
	t.Run("returns the value of the last header with the key", func(t *testing.T) {
		key := "messageType"
		headers := []kafkago.Header{{Key: key, Value: []byte("First")}, {Key: key, Value: []byte("Last")}}

		got, ok := headerValue(headers, key)

		if !ok || got != "Last" {
			t.Errorf("headerValue = %q (found: %v), want %q", got, ok, "Last")
		}
	})

	t.Run("reports no header with the key", func(t *testing.T) {
		if _, ok := headerValue(nil, "messageType"); ok {
			t.Error("found = true, want false")
		}
	})
}

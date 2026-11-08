package kafkago

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	inkafka "inventoryservice/interfaces/kafka"
	"log/slog"
	"slices"
	"testing"
	"time"

	"inventoryservice/application/port/common/kafka"
	"inventoryservice/application/port/common/logging"

	kafkago "github.com/segmentio/kafka-go"

	"github.com/google/uuid"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"
)

// mockDeadLetterProducer records the messages published to it, failing the first failFirst calls.
type mockDeadLetterProducer struct {
	deadLetterProducer
	failFirst  int
	calls      int
	closeCalls int
	published  []kafkago.Message
}

func (f *mockDeadLetterProducer) Send(_ context.Context, message kafkago.Message) error {
	f.calls++
	if f.calls <= f.failFirst {
		return errors.New("dead-letter topic unavailable")
	}

	f.published = append(f.published, message)
	return nil
}

func (f *mockDeadLetterProducer) Close() error {
	f.closeCalls++
	return nil
}

func TestParseHeaders(t *testing.T) {
	t.Run("extracts messageType, messageId and correlationId when all headers are present", func(t *testing.T) {
		wantMessageType := kafka.CancelReservationCommand
		wantMessageID := uuid.New()
		wantCorrelationID := "corr-1"
		headers := []kafkago.Header{
			{Key: kafka.MessageTypeHeader, Value: []byte(wantMessageType.String())},
			{Key: kafka.MessageIDHeader, Value: []byte(wantMessageID.String())},
			{Key: kafka.CorrelationIDHeader, Value: []byte(wantCorrelationID)},
		}

		gotMessageType, gotMessageID, gotCorrelationID, err := parseHeaders(headers)
		if err != nil {
			t.Fatalf("err = %v, want nil", err)
		}
		if gotMessageType != wantMessageType {
			t.Errorf("messageType = %v, want %v", gotMessageType, wantMessageType)
		}
		if gotMessageID != wantMessageID {
			t.Errorf("messageID = %v, want %v", gotMessageID, wantMessageID)
		}
		if gotCorrelationID != wantCorrelationID {
			t.Errorf("correlationID = %v, want %v", gotCorrelationID, wantCorrelationID)
		}
	})

	t.Run("ignores unknown headers while extracting the required ones", func(t *testing.T) {
		wantMessageType := kafka.CancelReservationCommand
		wantMessageID := uuid.New()
		wantCorrelationID := "corr-1"
		headers := []kafkago.Header{
			{Key: "someOtherHeader", Value: []byte("ignored")},
			{Key: kafka.MessageTypeHeader, Value: []byte(wantMessageType.String())},
			{Key: kafka.MessageIDHeader, Value: []byte(wantMessageID.String())},
			{Key: kafka.CorrelationIDHeader, Value: []byte(wantCorrelationID)},
		}

		gotMessageType, gotMessageID, gotCorrelationID, err := parseHeaders(headers)
		if err != nil {
			t.Fatalf("err = %v, want nil", err)
		}
		if gotMessageType != wantMessageType {
			t.Errorf("messageType = %v, want %v", gotMessageType, wantMessageType)
		}
		if gotMessageID != wantMessageID {
			t.Errorf("messageID = %v, want %v", gotMessageID, wantMessageID)
		}
		if gotCorrelationID != wantCorrelationID {
			t.Errorf("correlationID = %v, want %v", gotCorrelationID, wantCorrelationID)
		}
	})

	t.Run("returns an error when the messageType header is missing", func(t *testing.T) {
		headers := []kafkago.Header{
			{Key: kafka.MessageIDHeader, Value: []byte(uuid.NewString())},
			{Key: kafka.CorrelationIDHeader, Value: []byte("corr-1")},
		}

		if _, _, _, err := parseHeaders(headers); err == nil {
			t.Error("err = nil, want an error")
		}
	})

	t.Run("returns an error when the messageId header is missing", func(t *testing.T) {
		headers := []kafkago.Header{
			{Key: kafka.MessageTypeHeader, Value: []byte(kafka.CancelReservationCommand.String())},
			{Key: kafka.CorrelationIDHeader, Value: []byte("corr-1")},
		}

		if _, _, _, err := parseHeaders(headers); err == nil {
			t.Error("err = nil, want an error")
		}
	})

	t.Run("returns an error when the messageId header is not a UUID", func(t *testing.T) {
		headers := []kafkago.Header{
			{Key: kafka.MessageTypeHeader, Value: []byte(kafka.CancelReservationCommand.String())},
			{Key: kafka.MessageIDHeader, Value: []byte("not-a-uuid")},
			{Key: kafka.CorrelationIDHeader, Value: []byte("corr-1")},
		}

		if _, _, _, err := parseHeaders(headers); err == nil {
			t.Error("err = nil, want an error")
		}
	})

	t.Run("returns an error when the correlationId header is missing", func(t *testing.T) {
		headers := []kafkago.Header{
			{Key: kafka.MessageTypeHeader, Value: []byte(kafka.CancelReservationCommand.String())},
			{Key: kafka.MessageIDHeader, Value: []byte(uuid.NewString())},
		}

		if _, _, _, err := parseHeaders(headers); err == nil {
			t.Error("err = nil, want an error")
		}
	})

	t.Run("returns an error when no headers are present", func(t *testing.T) {
		if _, _, _, err := parseHeaders(nil); err == nil {
			t.Error("err = nil, want an error")
		}
	})
}

func TestKafkaGoMessageConsumer_process(t *testing.T) {
	validMessage := func() kafkago.Message {
		return kafkago.Message{
			Topic:     kafka.InventoryCommandsTopic,
			Partition: 0,
			Offset:    5,
			Key:       []byte("7"),
			Value:     []byte(`{"orderId":7}`),
			Headers: []kafkago.Header{
				{Key: kafka.MessageTypeHeader, Value: []byte(kafka.CancelReservationCommand.String())},
				{Key: kafka.MessageIDHeader, Value: []byte(uuid.NewString())},
				{Key: kafka.CorrelationIDHeader, Value: []byte("corr-1")},
			},
		}
	}
	newConsumer := func(producer deadLetterProducer) *kafkaGoMessageConsumer {
		return &kafkaGoMessageConsumer{
			groupID:            "inventory-service",
			deadLetterProducer: producer,
			retryDelays:        backoffDelays(maxRetries, time.Millisecond),
		}
	}

	t.Run("returns true without dead-lettering when the handler succeeds", func(t *testing.T) {
		producer := &mockDeadLetterProducer{}
		gotHandlerCalls := 0

		got := newConsumer(producer).process(context.Background(), validMessage(), func(context.Context, kafka.MessageType, uuid.UUID, string, []byte) error {
			gotHandlerCalls++
			return nil
		})

		if !got {
			t.Error("process = false, want true")
		}
		if wantHandlerCalls := 1; gotHandlerCalls != wantHandlerCalls {
			t.Errorf("handler calls = %d, want %d", gotHandlerCalls, wantHandlerCalls)
		}
		if wantPublishCalls := 0; producer.calls != wantPublishCalls {
			t.Errorf("dead-letter publishes = %d, want %d", producer.calls, wantPublishCalls)
		}
	})

	t.Run("retries a transient failure and returns true without dead-lettering once the handler succeeds", func(t *testing.T) {
		producer := &mockDeadLetterProducer{}
		failures := 2
		gotHandlerCalls := 0

		got := newConsumer(producer).process(context.Background(), validMessage(), func(context.Context, kafka.MessageType, uuid.UUID, string, []byte) error {
			gotHandlerCalls++
			if gotHandlerCalls <= failures {
				return errors.New("transient failure")
			}
			return nil
		})

		if !got {
			t.Error("process = false, want true")
		}
		if wantHandlerCalls := failures + 1; gotHandlerCalls != wantHandlerCalls {
			t.Errorf("handler calls = %d, want %d", gotHandlerCalls, wantHandlerCalls)
		}
		if wantPublishCalls := 0; producer.calls != wantPublishCalls {
			t.Errorf("dead-letter publishes = %d, want %d", producer.calls, wantPublishCalls)
		}
	})

	t.Run("dead-letters the message after every attempt fails", func(t *testing.T) {
		producer := &mockDeadLetterProducer{}
		source := validMessage()
		gotHandlerCalls := 0

		got := newConsumer(producer).process(context.Background(), source, func(context.Context, kafka.MessageType, uuid.UUID, string, []byte) error {
			gotHandlerCalls++
			return errors.New("transient failure")
		})

		if !got {
			t.Error("process = false, want true")
		}
		if wantHandlerCalls := maxRetries + 1; gotHandlerCalls != wantHandlerCalls {
			t.Errorf("handler calls = %d, want %d", gotHandlerCalls, wantHandlerCalls)
		}
		if wantPublishCalls := 1; producer.calls != wantPublishCalls {
			t.Fatalf("dead-letter publishes = %d, want %d", producer.calls, wantPublishCalls)
		}
		if gotTopic := string(singleHeader(t, producer.published[0].Headers, kafka.DLTOriginalTopicHeader)); gotTopic != source.Topic {
			t.Errorf("original topic = %q, want %q", gotTopic, source.Topic)
		}
	})

	t.Run("dead-letters a failure of a permanent category after a single attempt", func(t *testing.T) {
		producer := &mockDeadLetterProducer{}
		gotHandlerCalls := 0

		got := newConsumer(producer).process(context.Background(), validMessage(), func(context.Context, kafka.MessageType, uuid.UUID, string, []byte) error {
			gotHandlerCalls++
			return inkafka.ErrMalformedMessage.WithCause(errors.New("permanent failure"))
		})

		if !got {
			t.Error("process = false, want true")
		}
		if wantHandlerCalls := 1; gotHandlerCalls != wantHandlerCalls {
			t.Errorf("handler calls = %d, want %d", gotHandlerCalls, wantHandlerCalls)
		}
		if wantPublishCalls := 1; producer.calls != wantPublishCalls {
			t.Errorf("dead-letter publishes = %d, want %d", producer.calls, wantPublishCalls)
		}
	})

	t.Run("retries and then dead-letters a handler that panics, as an internal failure", func(t *testing.T) {
		producer := &mockDeadLetterProducer{}
		consumer := newConsumer(producer)
		consumer.retryDelays = []time.Duration{time.Millisecond}
		gotHandlerCalls := 0

		got := consumer.process(context.Background(), validMessage(), func(context.Context, kafka.MessageType, uuid.UUID, string, []byte) error {
			gotHandlerCalls++
			panic("boom")
		})

		if !got {
			t.Error("process = false, want true")
		}
		if wantHandlerCalls := 2; gotHandlerCalls != wantHandlerCalls {
			t.Errorf("handler calls = %d, want %d", gotHandlerCalls, wantHandlerCalls)
		}
		if wantPublishCalls := 1; producer.calls != wantPublishCalls {
			t.Errorf("dead-letter publishes = %d, want %d", producer.calls, wantPublishCalls)
		}
	})

	t.Run("dead-letters a message with invalid headers without invoking the handler", func(t *testing.T) {
		producer := &mockDeadLetterProducer{}
		message := validMessage()
		message.Headers = nil
		gotHandlerCalls := 0

		got := newConsumer(producer).process(context.Background(), message, func(context.Context, kafka.MessageType, uuid.UUID, string, []byte) error {
			gotHandlerCalls++
			return nil
		})

		if !got {
			t.Error("process = false, want true")
		}
		if wantHandlerCalls := 0; gotHandlerCalls != wantHandlerCalls {
			t.Errorf("handler calls = %d, want %d", gotHandlerCalls, wantHandlerCalls)
		}
		if wantPublishCalls := 1; producer.calls != wantPublishCalls {
			t.Errorf("dead-letter publishes = %d, want %d", producer.calls, wantPublishCalls)
		}
	})

	t.Run("returns false without dead-lettering when the context is cancelled while waiting to retry the handler", func(t *testing.T) {
		producer := &mockDeadLetterProducer{}
		consumer := newConsumer(producer)
		consumer.retryDelays = []time.Duration{time.Hour}
		ctx, cancel := context.WithCancel(context.Background())
		gotHandlerCalls := 0

		got := consumer.process(ctx, validMessage(), func(context.Context, kafka.MessageType, uuid.UUID, string, []byte) error {
			gotHandlerCalls++
			cancel()
			return errors.New("transient failure")
		})

		if got {
			t.Error("process = true, want false")
		}
		if wantHandlerCalls := 1; gotHandlerCalls != wantHandlerCalls {
			t.Errorf("handler calls = %d, want %d", gotHandlerCalls, wantHandlerCalls)
		}
		if wantPublishCalls := 0; producer.calls != wantPublishCalls {
			t.Errorf("dead-letter publishes = %d, want %d", producer.calls, wantPublishCalls)
		}
	})

	// Retrying a failed dead-letter publish is now deadLetterRetryableProducer's own responsibility
	// (see TestDeadLetterRetryableProducer_Send in kafkago_dead_letter_test.go) - process only needs to
	// react to whatever Send finally returns.
	t.Run("returns false without dead-lettering when the dead-letter publish fails", func(t *testing.T) {
		producer := &mockDeadLetterProducer{failFirst: 1 << 30}

		got := newConsumer(producer).process(context.Background(), validMessage(), func(context.Context, kafka.MessageType, uuid.UUID, string, []byte) error {
			return inkafka.ErrMalformedMessage.WithCause(errors.New("permanent failure"))
		})

		if got {
			t.Error("process = true, want false")
		}
		if wantPublishCalls := 1; producer.calls != wantPublishCalls {
			t.Errorf("dead-letter publishes = %d, want %d", producer.calls, wantPublishCalls)
		}
	})

	t.Run("handles the message in a child span of the one its traceparent header names", func(t *testing.T) {
		recorder := recordSpans()
		traceID := "4bf92f3577b34da6a3ce929d0e0e4736"
		parentSpanID := "00f067aa0ba902b7"
		message := validMessage()
		message.Headers = append(message.Headers, kafkago.Header{Key: traceparentHeader, Value: []byte("00-" + traceID + "-" + parentSpanID + "-01")})
		var gotHandlerTraceID string

		processed := newConsumer(&mockDeadLetterProducer{}).process(context.Background(), message, func(ctx context.Context, _ kafka.MessageType, _ uuid.UUID, _ string, _ []byte) error {
			gotHandlerTraceID = trace.SpanContextFromContext(ctx).TraceID().String()
			return nil
		})

		if !processed {
			t.Error("process = false, want true")
		}
		if gotHandlerTraceID != traceID {
			t.Errorf("handler's trace id = %q, want %q", gotHandlerTraceID, traceID)
		}
		endedSpan := endedSpanOfTrace(t, recorder, traceID)
		if gotParentSpanID := endedSpan.Parent().SpanID().String(); gotParentSpanID != parentSpanID {
			t.Errorf("parent span id = %q, want %q", gotParentSpanID, parentSpanID)
		}
		if wantKind := trace.SpanKindConsumer; endedSpan.SpanKind() != wantKind {
			t.Errorf("span kind = %v, want %v", endedSpan.SpanKind(), wantKind)
		}
		if wantCode := codes.Unset; endedSpan.Status().Code != wantCode {
			t.Errorf("span status = %v, want %v", endedSpan.Status().Code, wantCode)
		}
	})

	t.Run("hands the handler a context with the message's ids for its log lines", func(t *testing.T) {
		messageID := uuid.NewString()
		correlationID := "corr-logs"
		message := validMessage()
		message.Headers = []kafkago.Header{
			{Key: kafka.MessageTypeHeader, Value: []byte(kafka.CancelReservationCommand.String())},
			{Key: kafka.MessageIDHeader, Value: []byte(messageID)},
			{Key: kafka.CorrelationIDHeader, Value: []byte(correlationID)},
		}
		var gotAttrs []slog.Attr

		processed := newConsumer(&mockDeadLetterProducer{}).process(context.Background(), message, func(ctx context.Context, _ kafka.MessageType, _ uuid.UUID, _ string, _ []byte) error {
			gotAttrs = logging.Attrs(ctx)
			return nil
		})

		if !processed {
			t.Error("process = false, want true")
		}
		wantAttrs := []slog.Attr{
			slog.String(kafka.MessageTypeHeader, kafka.CancelReservationCommand.String()),
			slog.String(kafka.MessageIDHeader, messageID),
			slog.String(kafka.CorrelationIDHeader, correlationID),
		}
		if !slices.EqualFunc(gotAttrs, wantAttrs, slog.Attr.Equal) {
			t.Errorf("handler's log attrs = %v, want %v", gotAttrs, wantAttrs)
		}
	})

	t.Run("marks the span failed when the message is dead-lettered", func(t *testing.T) {
		recorder := recordSpans()
		traceID := "5bf92f3577b34da6a3ce929d0e0e4736"
		message := validMessage()
		message.Headers = append(message.Headers, kafkago.Header{Key: traceparentHeader, Value: []byte("00-" + traceID + "-00f067aa0ba902b7-01")})

		processed := newConsumer(&mockDeadLetterProducer{}).process(context.Background(), message, func(context.Context, kafka.MessageType, uuid.UUID, string, []byte) error {
			return inkafka.ErrMalformedMessage.WithCause(errors.New("permanent failure"))
		})

		if !processed {
			t.Error("process = false, want true")
		}
		if wantCode := codes.Error; endedSpanOfTrace(t, recorder, traceID).Status().Code != wantCode {
			t.Errorf("span status = %v, want %v", endedSpanOfTrace(t, recorder, traceID).Status().Code, wantCode)
		}
	})
}

func TestLogFailure(t *testing.T) {
	logLine := func(t *testing.T, err error) map[string]any {
		t.Helper()
		var buffer bytes.Buffer
		previous := slog.Default()
		slog.SetDefault(slog.New(slog.NewJSONHandler(&buffer, nil)))
		defer slog.SetDefault(previous)

		logFailure(context.Background(), err)

		var line map[string]any
		if err = json.Unmarshal(buffer.Bytes(), &line); err != nil {
			t.Fatalf("log line %q is not JSON: %v", buffer.String(), err)
		}
		return line
	}

	t.Run("logs a permanent failure as a rejected message, at warn", func(t *testing.T) {
		line := logLine(t, inkafka.ErrMalformedMessage)

		if got, want := line[slog.MessageKey], "kafka message rejected"; got != want {
			t.Errorf("message = %v, want %q", got, want)
		}
		if got, want := line[slog.LevelKey], slog.LevelWarn.String(); got != want {
			t.Errorf("level = %v, want %q", got, want)
		}
	})

	t.Run("logs a transient failure as a failed attempt, at warn", func(t *testing.T) {
		line := logLine(t, errors.New("database unavailable"))

		if got, want := line[slog.MessageKey], "kafka message handling failed"; got != want {
			t.Errorf("message = %v, want %q", got, want)
		}
		if got, want := line[slog.LevelKey], slog.LevelWarn.String(); got != want {
			t.Errorf("level = %v, want %q", got, want)
		}
	})
}

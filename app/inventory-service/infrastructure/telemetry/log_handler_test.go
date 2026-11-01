package telemetry

import (
	"bytes"
	"context"
	"encoding/json"
	"inventoryservice/application/port/common/logging"
	"log/slog"
	"testing"

	"go.opentelemetry.io/otel/trace"
)

// logLine logs one line through a log handler and returns it decoded.
func logLine(t *testing.T, ctx context.Context, message string, args ...any) map[string]any {
	t.Helper()
	var buffer bytes.Buffer
	slog.New(NewLogHandler(&buffer, slog.LevelDebug)).InfoContext(ctx, message, args...)

	var line map[string]any
	if err := json.Unmarshal(buffer.Bytes(), &line); err != nil {
		t.Fatalf("log line %q is not JSON: %v", buffer.String(), err)
	}
	return line
}

func TestNewLogHandler(t *testing.T) {
	t.Run("writes a JSON line with the Spring services' field names", func(t *testing.T) {
		message := "successfully processed kafka message"

		line := logLine(t, context.Background(), message)

		if got := line[messageKey]; got != message {
			t.Errorf("%s = %v, want %q", messageKey, got, message)
		}
		if _, ok := line[timestampKey]; !ok {
			t.Errorf("no %s in %v", timestampKey, line)
		}
		if got := line[slog.LevelKey]; got != slog.LevelInfo.String() {
			t.Errorf("%s = %v, want %q", slog.LevelKey, got, slog.LevelInfo.String())
		}
	})
}

func TestLogHandler_Handle(t *testing.T) {
	t.Run("adds the trace and span of the context", func(t *testing.T) {
		traceID := trace.TraceID{0x0a, 0xf7, 0x65, 0x19, 0x16, 0xcd, 0x43, 0xdd, 0x84, 0x48, 0xeb, 0x21, 0x1c, 0x80, 0x31, 0x9c}
		spanID := trace.SpanID{0xb7, 0xad, 0x6b, 0x71, 0x69, 0x20, 0x33, 0x31}
		ctx := trace.ContextWithSpanContext(context.Background(), trace.NewSpanContext(trace.SpanContextConfig{
			TraceID: traceID, SpanID: spanID, TraceFlags: trace.FlagsSampled,
		}))

		line := logLine(t, ctx, "handled")

		if got := line[traceIDKey]; got != traceID.String() {
			t.Errorf("%s = %v, want %q", traceIDKey, got, traceID.String())
		}
		if got := line[spanIDKey]; got != spanID.String() {
			t.Errorf("%s = %v, want %q", spanIDKey, got, spanID.String())
		}
	})

	t.Run("adds no trace without a span in the context", func(t *testing.T) {
		line := logLine(t, context.Background(), "swept")

		if _, ok := line[traceIDKey]; ok {
			t.Errorf("%s in %v, want none", traceIDKey, line)
		}
		if _, ok := line[spanIDKey]; ok {
			t.Errorf("%s in %v, want none", spanIDKey, line)
		}
	})

	t.Run("adds the attrs of the context", func(t *testing.T) {
		correlationID := "corr-1"
		ctx := logging.WithAttrs(context.Background(), slog.String("correlationId", correlationID))

		line := logLine(t, ctx, "received kafka message")

		if got := line["correlationId"]; got != correlationID {
			t.Errorf("correlationId = %v, want %q", got, correlationID)
		}
	})

	t.Run("keeps an attr logged explicitly over the context's of the same key", func(t *testing.T) {
		sentMessageType := "ReservationHeld"
		ctx := logging.WithAttrs(context.Background(), slog.String("messageType", "HoldReservation"))

		var buffer bytes.Buffer
		slog.New(NewLogHandler(&buffer, slog.LevelDebug)).InfoContext(ctx, "sending kafka message", "messageType", sentMessageType)

		// decoding into a map would keep only one of two equal keys, so the keys are counted on the raw line
		if got := bytes.Count(buffer.Bytes(), []byte(`"messageType"`)); got != 1 {
			t.Errorf("messageType appears %d times in %s, want once", got, buffer.String())
		}
		var line map[string]any
		if err := json.Unmarshal(buffer.Bytes(), &line); err != nil {
			t.Fatalf("log line %q is not JSON: %v", buffer.String(), err)
		}
		if got := line["messageType"]; got != sentMessageType {
			t.Errorf("messageType = %v, want %q", got, sentMessageType)
		}
	})
}

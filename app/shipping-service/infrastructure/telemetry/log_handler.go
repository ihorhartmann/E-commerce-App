package telemetry

import (
	"context"
	"io"
	"log/slog"
	"shippingservice/application/port/common/logging"

	"go.opentelemetry.io/otel/trace"
)

// The field names of the Spring services' logstash format - @timestamp, message, and traceId and spanId from their
// MDC - so one query finds a field in the logs of every service.
const (
	timestampKey = "@timestamp"
	messageKey   = "message"
	traceIDKey   = "traceId"
	spanIDKey    = "spanId"
)

// logHandler writes a record as one JSON object per line - what Alloy reads from stdout and pushes to Loki - with the
// trace and span it was logged in, and the attrs its context carries from logging.WithAttrs. Both come from the ctx of
// slog's *Context functions: slog.Info, without one, logs neither.
type logHandler struct {
	slog.Handler
}

func NewLogHandler(writer io.Writer, level slog.Leveler) slog.Handler {
	return logHandler{Handler: slog.NewJSONHandler(writer, &slog.HandlerOptions{Level: level, ReplaceAttr: renameBuiltInAttr})}
}

func (h logHandler) Handle(ctx context.Context, record slog.Record) error {
	if span := trace.SpanContextFromContext(ctx); span.IsValid() {
		record.AddAttrs(slog.String(traceIDKey, span.TraceID().String()), slog.String(spanIDKey, span.SpanID().String()))
	}
	record.AddAttrs(missingAttrs(record, logging.Attrs(ctx))...)
	return h.Handler.Handle(ctx, record)
}

func (h logHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	return logHandler{Handler: h.Handler.WithAttrs(attrs)}
}

func (h logHandler) WithGroup(name string) slog.Handler {
	return logHandler{Handler: h.Handler.WithGroup(name)}
}

func renameBuiltInAttr(groups []string, attr slog.Attr) slog.Attr {
	if len(groups) > 0 {
		return attr
	}
	switch attr.Key {
	case slog.TimeKey:
		attr.Key = timestampKey
	case slog.MessageKey:
		attr.Key = messageKey
	}
	return attr
}

// missingAttrs is those of attrs whose keys record has none of: a key logged explicitly - the type of a message a
// handler sends, say - wins over the same key from the context, instead of the line getting it twice.
func missingAttrs(record slog.Record, attrs []slog.Attr) []slog.Attr {
	if len(attrs) == 0 {
		return nil
	}

	keys := make(map[string]bool, record.NumAttrs())
	record.Attrs(func(attr slog.Attr) bool {
		keys[attr.Key] = true
		return true
	})

	var missing []slog.Attr
	for _, attr := range attrs {
		if !keys[attr.Key] {
			missing = append(missing, attr)
		}
	}
	return missing
}

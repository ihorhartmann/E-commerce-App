package logging

import (
	"context"
	"log/slog"
	"slices"
	"sync/atomic"
)

const CorrelationIDKey = "correlationId"

type attrsKey struct{}

type correlationKey struct{}

// WithAttrs returns a copy of ctx carrying attrs on top of those ctx carries already. The log handler adds them to
// every line logged with the context - a Kafka message's ids, say, to every line logged while it is handled.
func WithAttrs(ctx context.Context, attrs ...slog.Attr) context.Context {
	return context.WithValue(ctx, attrsKey{}, slices.Concat(ownAttrs(ctx), attrs))
}

// Attrs returns the attrs ctx carries, from WithAttrs, and the correlation id its unit of work has - from
// WithCorrelationID or ResolveCorrelationID - unless an attr of WithAttrs already carries one.
func Attrs(ctx context.Context) []slog.Attr {
	attrs := ownAttrs(ctx)
	if hasCorrelationID(attrs) {
		return attrs
	}
	if id := keyCorrelationID(ctx).get(); id != "" {
		return append(slices.Clip(attrs), slog.String(CorrelationIDKey, id))
	}
	return attrs
}

// correlation is the correlation id of one unit of work - an HTTP request - which may learn it only as it runs: a
// pointer, set at most once, shared by every context derived from the request's, including the one its middleware
// logs with once the request is handled.
type correlation struct {
	id atomic.Pointer[string]
}

func (c *correlation) get() string {
	if c == nil {
		return ""
	}
	if id := c.id.Load(); id != nil {
		return *id
	}
	return ""
}

// WithCorrelationID returns a copy of ctx for a unit of work - an HTTP request - that came with id as the saga's
// correlation id, which every line logged with the context then carries; with an empty id, the lines carry none until
// ResolveCorrelationID gives it one.
func WithCorrelationID(ctx context.Context, id string) context.Context {
	corr := &correlation{}
	if id != "" {
		corr.id.Store(&id)
	}
	return context.WithValue(ctx, correlationKey{}, corr)
}

// ResolveCorrelationID returns the saga's correlation id of the unit of work of ctx: the one it came with - in a Kafka
// message's or an HTTP request's header - or, when it came with none, fallback, which every line it logs from then on
// carries too.
func ResolveCorrelationID(ctx context.Context, fallback string) string {
	if id, ok := attrCorrelationID(ctx); ok {
		return id
	}
	corr := keyCorrelationID(ctx)
	if corr == nil {
		return fallback
	}
	if corr.id.CompareAndSwap(nil, &fallback) {
		return fallback
	}
	return corr.get()
}

func keyCorrelationID(ctx context.Context) *correlation {
	c, _ := ctx.Value(correlationKey{}).(*correlation)
	return c
}

// ownAttrs returns the attrs ctx carries from WithAttrs alone.
func ownAttrs(ctx context.Context) []slog.Attr {
	attrs, _ := ctx.Value(attrsKey{}).([]slog.Attr)
	return attrs
}

func attrCorrelationID(ctx context.Context) (string, bool) {
	for _, attr := range ownAttrs(ctx) {
		if attr.Key == CorrelationIDKey {
			return attr.Value.String(), true
		}
	}
	return "", false
}

func hasCorrelationID(attrs []slog.Attr) bool {
	return slices.ContainsFunc(attrs, func(attr slog.Attr) bool { return attr.Key == CorrelationIDKey })
}

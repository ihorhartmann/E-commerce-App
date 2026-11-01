package logging

import (
	"context"
	"log/slog"
	"slices"
)

type attrsKey struct{}

// WithAttrs returns a copy of ctx carrying attrs on top of those ctx carries already. The log handler adds them to
// every line logged with the context - a Kafka message's ids, say, to every line logged while it is handled.
func WithAttrs(ctx context.Context, attrs ...slog.Attr) context.Context {
	return context.WithValue(ctx, attrsKey{}, slices.Concat(Attrs(ctx), attrs))
}

// Attrs returns the attrs ctx carries, from WithAttrs.
func Attrs(ctx context.Context) []slog.Attr {
	attrs, _ := ctx.Value(attrsKey{}).([]slog.Attr)
	return attrs
}

package logging

import (
	"context"
	"log/slog"
	"slices"
	"testing"
)

func TestWithAttrs(t *testing.T) {
	t.Run("adds the attrs to those the context carries already", func(t *testing.T) {
		first := slog.String("correlationId", "corr-1")
		second := slog.String("messageId", "message-1")

		got := Attrs(WithAttrs(WithAttrs(context.Background(), first), second))

		want := []slog.Attr{first, second}
		if !slices.EqualFunc(got, want, slog.Attr.Equal) {
			t.Errorf("Attrs = %v, want %v", got, want)
		}
	})

	t.Run("leaves the attrs of the context it is given as they are", func(t *testing.T) {
		parentAttr := slog.String("correlationId", "corr-2")
		parentCtx := WithAttrs(context.Background(), parentAttr)

		WithAttrs(parentCtx, slog.String("messageId", "message-2"))
		WithAttrs(parentCtx, slog.String("messageId", "message-3"))

		want := []slog.Attr{parentAttr}
		if got := Attrs(parentCtx); !slices.EqualFunc(got, want, slog.Attr.Equal) {
			t.Errorf("Attrs(parent) = %v, want %v", got, want)
		}
	})
}

func TestAttrs(t *testing.T) {
	t.Run("is empty for a context without attrs", func(t *testing.T) {
		if got := Attrs(context.Background()); len(got) != 0 {
			t.Errorf("Attrs = %v, want none", got)
		}
	})
}

func TestAttrs_CorrelationID(t *testing.T) {
	t.Run("adds the correlation id of the unit of work", func(t *testing.T) {
		correlationID := "42"
		ctx := WithCorrelationID(context.Background(), correlationID)

		got := Attrs(ctx)

		want := []slog.Attr{slog.String(CorrelationIDKey, correlationID)}
		if !slices.EqualFunc(got, want, slog.Attr.Equal) {
			t.Errorf("Attrs = %v, want %v", got, want)
		}
	})

	t.Run("keeps a correlation id of WithAttrs instead of the unit of work's", func(t *testing.T) {
		attr := slog.String(CorrelationIDKey, "41")
		ctx := WithAttrs(WithCorrelationID(context.Background(), "42"), attr)

		got := Attrs(ctx)

		want := []slog.Attr{attr}
		if !slices.EqualFunc(got, want, slog.Attr.Equal) {
			t.Errorf("Attrs = %v, want %v", got, want)
		}
	})

	t.Run("adds none while the unit of work has none", func(t *testing.T) {
		if got := Attrs(WithCorrelationID(context.Background(), "")); len(got) != 0 {
			t.Errorf("Attrs = %v, want none", got)
		}
	})
}

func TestResolveCorrelationID(t *testing.T) {
	t.Run("returns the correlation id the unit of work came with", func(t *testing.T) {
		correlationID := "41"
		ctx := WithCorrelationID(context.Background(), correlationID)

		got := ResolveCorrelationID(ctx, "42")

		if got != correlationID {
			t.Errorf("ResolveCorrelationID = %q, want %q", got, correlationID)
		}
	})

	t.Run("returns the correlation id of a Kafka message's attrs", func(t *testing.T) {
		correlationID := "41"
		ctx := WithAttrs(context.Background(), slog.String(CorrelationIDKey, correlationID))

		got := ResolveCorrelationID(ctx, "42")

		if got != correlationID {
			t.Errorf("ResolveCorrelationID = %q, want %q", got, correlationID)
		}
	})

	t.Run("gives a unit of work without one the fallback, for every line it logs from then on", func(t *testing.T) {
		requestCtx := WithCorrelationID(context.Background(), "")
		fallback := "42"

		gotCorrelationID := ResolveCorrelationID(context.WithValue(requestCtx, struct{}{}, "derived"), fallback)

		if gotCorrelationID != fallback {
			t.Errorf("ResolveCorrelationID = %q, want %q", gotCorrelationID, fallback)
		}
		wantAttrs := []slog.Attr{slog.String(CorrelationIDKey, fallback)}
		if gotAttrs := Attrs(requestCtx); !slices.EqualFunc(gotAttrs, wantAttrs, slog.Attr.Equal) {
			t.Errorf("Attrs(request) = %v, want %v", gotAttrs, wantAttrs)
		}
	})

	t.Run("returns the fallback outside a unit of work", func(t *testing.T) {
		fallback := "42"

		got := ResolveCorrelationID(context.Background(), fallback)

		if got != fallback {
			t.Errorf("ResolveCorrelationID = %q, want %q", got, fallback)
		}
	})
}

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

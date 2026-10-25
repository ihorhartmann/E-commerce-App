package apperror_test

import (
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"testing"

	"inventoryservice/domain/apperror"
)

// Package-level, like real sentinels: errors.Is matches the occurrences of one sentinel, so every
// test must use the same sentinel.
var (
	errTestConflict        = apperror.New("TEST_CONFLICT", apperror.CategoryConflict, "Test conflict")
	errTestNotFound        = apperror.New("TEST_NOT_FOUND", apperror.CategoryNotFound, "Test not found")
	errTestTransient       = apperror.New("TEST_TRANSIENT", apperror.CategoryTransient, "Test transient")
	errTestDatabaseFailure = apperror.New("TEST_DATABASE_FAILURE", apperror.CategoryInternal, "Test database failure")
)

func TestNew(t *testing.T) {
	t.Run("panics on a code that already has a prefix", func(t *testing.T) {
		defer func() {
			if recover() == nil {
				t.Fatal("expected a panic")
			}
		}()

		_ = apperror.New("INVENTORY.TEST_PREFIXED", apperror.CategoryConflict, "Prefixed")
	})

	t.Run("prefixes the code with the service", func(t *testing.T) {
		want := "INVENTORY.TEST_CONFLICT"
		if got := errTestConflict.Code(); got != want {
			t.Fatalf("got %q, want %q", got, want)
		}
	})

	t.Run("panics on an empty code", func(t *testing.T) {
		defer func() {
			if recover() == nil {
				t.Fatal("expected a panic")
			}
		}()

		_ = apperror.New(" ", apperror.CategoryConflict, "Blank")
	})
}

func TestError_Is(t *testing.T) {
	t.Run("matches a bare sentinel", func(t *testing.T) {
		var err error = errTestConflict

		if !errors.Is(err, errTestConflict) {
			t.Fatal("expected errors.Is to match the sentinel")
		}
	})

	t.Run("matches an occurrence made by With methods", func(t *testing.T) {
		err := errTestConflict.WithInternalMessage("rejected").WithAttrs(slog.Int("productID", 7))

		if !errors.Is(err, errTestConflict) {
			t.Fatal("expected errors.Is to match the occurrence")
		}
	})

	t.Run("matches both the outer sentinel and the cause's through fmt.Errorf with %w", func(t *testing.T) {
		cause := errTestConflict.WithInternalMessage("rejected")
		err := fmt.Errorf("handle: %w", errTestTransient.WithInternalMessage("retry later").WithCause(cause))

		if !errors.Is(err, errTestTransient) {
			t.Fatal("expected errors.Is to match the outer sentinel")
		}
		if !errors.Is(err, errTestConflict) {
			t.Fatal("expected errors.Is to match the cause's sentinel")
		}
	})

	t.Run("does not match another sentinel of the same category", func(t *testing.T) {
		if errors.Is(errTestConflict.WithInternalMessage("rejected"), errTestNotFound) {
			t.Fatal("expected errors.Is not to match a different sentinel")
		}
	})

	t.Run("does not match a sentinel formatted with %v", func(t *testing.T) {
		err := fmt.Errorf("handle: %v", errTestConflict)

		if errors.Is(err, errTestConflict) {
			t.Fatal("expected formatting with a verb other than w to break the chain")
		}
	})
}

func TestError_With(t *testing.T) {
	t.Run("never changes the sentinel", func(t *testing.T) {
		_ = errTestConflict.
			WithInternalMessage("rejected").
			WithAttrs(slog.Int("productID", 7)).
			WithExternalMessage("not enough stock").
			WithCause(errors.New("cause"))

		if got := errTestConflict.Error(); got != errTestConflict.Code() {
			t.Errorf("got %q, want the bare sentinel's code", got)
		}
		if got := errTestConflict.ExternalMessage(); got != "" {
			t.Errorf("got external message %q, want none", got)
		}
		if _, ok := attrsOf(apperror.LogAttr(errTestConflict))["productID"]; ok {
			t.Error("expected the sentinel to get no attrs")
		}
	})

	t.Run("doesn't share attrs between copies of one occurrence", func(t *testing.T) {
		occurrence := errTestConflict.WithAttrs(slog.Int("productID", 7))
		_ = occurrence.WithAttrs(slog.Int("orderID", 3))

		if _, ok := attrsOf(apperror.LogAttr(occurrence))["orderID"]; ok {
			t.Fatal("expected an attr added to a copy not to reach the original")
		}
	})

	t.Run("is safe to call on a sentinel from many goroutines", func(t *testing.T) {
		var wait sync.WaitGroup
		for i := range 50 {
			wait.Go(func() {
				_ = errTestConflict.WithAttrs(slog.Int("i", i)).WithInternalMessage("concurrent")
			})
		}
		wait.Wait()

		if _, ok := attrsOf(apperror.LogAttr(errTestConflict))["i"]; ok {
			t.Fatal("expected the sentinel to get no attrs")
		}
	})

	t.Run("captures the stack trace at the first With call", func(t *testing.T) {
		stack := stackOf(t, failInHelper())

		if firstFrame, _, _ := strings.Cut(stack, "\n"); !strings.HasSuffix(firstFrame, ".failInHelper") {
			t.Fatalf("expected the stack trace to start in failInHelper, got:\n%s", stack)
		}
	})

	t.Run("keeps the stack trace closest to the failure", func(t *testing.T) {
		err := errTestDatabaseFailure.WithInternalMessage("outer").WithCause(failInHelper())

		stack := stackOf(t, err)

		if !strings.Contains(stack, "failInHelper") {
			t.Fatalf("expected the stack trace to start in failInHelper, got:\n%s", stack)
		}
	})
}

func TestOutermostError(t *testing.T) {
	t.Run("returns nil for a nil error", func(t *testing.T) {
		if got := apperror.OutermostError(nil); got != nil {
			t.Fatalf("got %v, want nil", got)
		}
	})

	t.Run("falls back to ErrInternal for an error without a sentinel", func(t *testing.T) {
		err := fmt.Errorf("load product: %w", errors.New("connection refused"))

		if got := apperror.OutermostError(err); !errors.Is(got, apperror.ErrInternal) {
			t.Fatalf("got %v, want %v", got, apperror.ErrInternal)
		}
	})

	t.Run("returns the outer occurrence, not its cause", func(t *testing.T) {
		cause := errTestNotFound.WithInternalMessage("no product")

		got := apperror.OutermostError(errTestTransient.WithInternalMessage("product not replicated yet").WithCause(cause))

		if !got.Is(errTestTransient) {
			t.Fatalf("got %v, want an occurrence of %v", got, errTestTransient)
		}
	})
}

func TestError_ExternalMessage(t *testing.T) {
	t.Run("returns the message set by WithExternalMessage", func(t *testing.T) {
		want := "page must be a positive integer"

		if got := errTestConflict.WithExternalMessage(want).ExternalMessage(); got != want {
			t.Fatalf("got %q, want %q", got, want)
		}
	})

	t.Run("does not take the cause's message", func(t *testing.T) {
		cause := errTestConflict.WithExternalMessage("not enough stock")

		if got := errTestTransient.WithCause(cause).ExternalMessage(); got != "" {
			t.Fatalf("got %q, want an empty message", got)
		}
	})

	t.Run("returns an empty message when none was set", func(t *testing.T) {
		if got := errTestConflict.WithAttrs(slog.Int("balance", 29)).ExternalMessage(); got != "" {
			t.Fatalf("got %q, want an empty message", got)
		}
	})
}

func TestError_Error(t *testing.T) {
	t.Run("is the code of a bare sentinel", func(t *testing.T) {
		want := "INVENTORY.TEST_CONFLICT"
		if got := errTestConflict.Error(); got != want {
			t.Fatalf("got %q, want %q", got, want)
		}
	})

	t.Run("joins message, code and cause", func(t *testing.T) {
		err := errTestTransient.WithInternalMessage("retry later").WithCause(errTestConflict.WithInternalMessage("rejected"))

		want := "retry later: INVENTORY.TEST_TRANSIENT: rejected: INVENTORY.TEST_CONFLICT"
		if got := err.Error(); got != want {
			t.Fatalf("got %q, want %q", got, want)
		}
	})
}

func TestFromPanic(t *testing.T) {
	t.Run("turns a panic into an internal error with the stack trace of the panic", func(t *testing.T) {
		err := recoverFrom(func() { panic(errTestConflict) })

		if !errors.Is(err, apperror.ErrInternal) {
			t.Fatalf("got %v, want an occurrence of %v", err, apperror.ErrInternal)
		}
		if errors.Is(err, errTestConflict) {
			t.Fatal("expected the panic value not to keep its own sentinel")
		}
		// The first frame is the deferred function in recoverFrom that called FromPanic.
		stack := stackOf(t, err)
		if firstFrame, _, _ := strings.Cut(stack, "\n"); !strings.Contains(firstFrame, ".recoverFrom.") {
			t.Fatalf("expected the stack trace to start in recoverFrom, got:\n%s", stack)
		}
	})
}

func TestLogAttr(t *testing.T) {
	t.Run("logs the stack trace only for an internal error", func(t *testing.T) {
		internal := attrsOf(apperror.LogAttr(errTestDatabaseFailure.WithCause(errors.New("deadlock"))))
		conflict := attrsOf(apperror.LogAttr(errTestConflict.WithInternalMessage("rejected")))

		if _, ok := internal["stack"]; !ok {
			t.Error("expected a stack trace for an internal error")
		}
		if _, ok := conflict["stack"]; ok {
			t.Error("expected no stack trace for a conflict")
		}
	})

	t.Run("logs the code, the category and the attrs of every error in the chain", func(t *testing.T) {
		cause := errTestNotFound.WithAttrs(slog.Int("productID", 7))
		err := errTestTransient.WithAttrs(slog.Int("orderID", 3)).WithCause(cause)

		got := attrsOf(apperror.LogAttr(err))

		want := map[string]string{"code": "INVENTORY.TEST_TRANSIENT", "category": "TRANSIENT", "productID": "7", "orderID": "3"}
		for key, wantValue := range want {
			if gotValue := got[key]; gotValue != wantValue {
				t.Errorf("%s: got %q, want %q", key, gotValue, wantValue)
			}
		}
		if _, ok := got["uncoded"]; ok {
			t.Error("expected no uncoded mark for an error with a sentinel")
		}
	})

	t.Run("logs the stack of every joined failure, however deep it lies", func(t *testing.T) {
		deepFailure := errTestDatabaseFailure.WithInternalMessage("reserve").WithCause(failInHelper())
		err := errTestDatabaseFailure.WithInternalMessage("sync").WithCause(errors.Join(deepFailure, failInOtherHelper()))

		gotFirstFrames := firstFramesOf(t, err)

		if len(gotFirstFrames) != 2 ||
			!strings.HasSuffix(gotFirstFrames[0], ".failInHelper") ||
			!strings.HasSuffix(gotFirstFrames[1], ".failInOtherHelper") {
			t.Fatalf("got first frames %v, want failInHelper and failInOtherHelper", gotFirstFrames)
		}
	})

	t.Run("gives a joined failure without an *Error the stack of the nearest error above it", func(t *testing.T) {
		err := errTestDatabaseFailure.WithInternalMessage("sync").WithCause(errors.Join(failInHelper(), errors.New("deadlock")))

		gotFirstFrames := firstFramesOf(t, err)

		if len(gotFirstFrames) != 2 ||
			!strings.HasSuffix(gotFirstFrames[0], ".failInHelper") ||
			!strings.Contains(gotFirstFrames[1], ".TestLogAttr.") {
			t.Fatalf("got first frames %v, want failInHelper and this test", gotFirstFrames)
		}
	})

	t.Run("logs each attr once however deep the error is wrapped", func(t *testing.T) {
		err := fmt.Errorf("outer: %w", fmt.Errorf("inner: %w", errTestConflict.WithAttrs(slog.Int("productID", 7))))

		count := 0
		for _, groupAttr := range apperror.LogAttr(err).Value.Group() {
			if groupAttr.Key == "productID" {
				count++
			}
		}

		if count != 1 {
			t.Fatalf("got productID %d times, want once", count)
		}
	})

	t.Run("marks an error without a sentinel as uncoded", func(t *testing.T) {
		got := attrsOf(apperror.LogAttr(errors.New("connection refused")))

		if gotUncoded := got["uncoded"]; gotUncoded != "true" {
			t.Fatalf("got uncoded %q, want true", gotUncoded)
		}
	})

	t.Run("logs no stack for an internal error that has none", func(t *testing.T) {
		got := attrsOf(apperror.LogAttr(errTestDatabaseFailure)) // a bare sentinel: no With call, no stack

		if _, ok := got["stack"]; ok {
			t.Error("expected no stack")
		}
		if _, ok := got["stacks"]; ok {
			t.Error("expected no list of stacks")
		}
	})

	t.Run("logs a stack shared by several joined failures once", func(t *testing.T) {
		// Both joined errors have no stack of their own, so both get the stack of the error above
		// the join - which must be logged once, as a single "stack".
		err := errTestDatabaseFailure.WithInternalMessage("sync").WithCause(errors.Join(errors.New("deadlock"), errors.New("timeout")))

		got := attrsOf(apperror.LogAttr(err))

		if _, ok := got["stacks"]; ok {
			t.Fatal("expected a single stack, not a list")
		}
		if firstFrame, _, _ := strings.Cut(got["stack"], "\n"); !strings.Contains(firstFrame, ".TestLogAttr.") {
			t.Fatalf("expected the stack trace to start in this test, got:\n%s", got["stack"])
		}
	})

	t.Run("logs a nil error as <nil>", func(t *testing.T) {
		want := "<nil>"
		if got := apperror.LogAttr(nil).Value.String(); got != want {
			t.Fatalf("got %q, want %q", got, want)
		}
	})
}

func TestError_LogValue(t *testing.T) {
	t.Run("renders the error as the same group LogAttr does", func(t *testing.T) {
		err := errTestConflict.WithInternalMessage("rejected").WithAttrs(slog.Int("productID", 7))

		got := map[string]string{}
		for _, groupAttr := range err.LogValue().Group() {
			got[groupAttr.Key] = groupAttr.Value.String()
		}

		want := map[string]string{"code": "INVENTORY.TEST_CONFLICT", "category": "CONFLICT", "productID": "7"}
		for key, wantValue := range want {
			if gotValue := got[key]; gotValue != wantValue {
				t.Errorf("%s: got %q, want %q", key, gotValue, wantValue)
			}
		}
	})
}

func failInHelper() error {
	return errTestDatabaseFailure.WithInternalMessage("write")
}

func failInOtherHelper() error {
	return errTestDatabaseFailure.WithInternalMessage("read")
}

// firstFramesOf returns the first frame of every stack in the "stacks" list logged for err.
func firstFramesOf(t *testing.T, err error) []string {
	t.Helper()
	for _, groupAttr := range apperror.LogAttr(err).Value.Group() {
		if groupAttr.Key != "stacks" {
			continue
		}
		stacks, ok := groupAttr.Value.Any().([]string)
		if !ok {
			t.Fatalf("got stacks of type %T, want []string", groupAttr.Value.Any())
		}
		firstFrames := make([]string, 0, len(stacks))
		for _, stack := range stacks {
			firstFrame, _, _ := strings.Cut(stack, "\n")
			firstFrames = append(firstFrames, firstFrame)
		}
		return firstFrames
	}

	t.Fatalf("expected %v to carry a list of stacks", err)
	return nil
}

func recoverFrom(fn func()) (err error) {
	defer func() {
		if recovered := recover(); recovered != nil {
			err = apperror.FromPanic(recovered)
		}
	}()
	fn()

	return nil
}

func attrsOf(attr slog.Attr) map[string]string {
	values := map[string]string{}
	for _, groupAttr := range attr.Value.Group() {
		values[groupAttr.Key] = groupAttr.Value.String()
	}

	return values
}

func stackOf(t *testing.T, err error) string {
	t.Helper()
	stack, ok := attrsOf(apperror.LogAttr(err))["stack"]
	if !ok {
		t.Fatalf("expected %v to carry a stack trace", err)
	}

	return stack
}

package apperror

import (
	"errors"
	"log/slog"
	"slices"
	"strings"
)

const servicePrefix = "SHIPPING."

// definition is what every occurrence of one sentinel shares: the stable, documented identifier
// sent to clients as ProblemDetail.code, its category and its public title.
type definition struct {
	code     string
	category Category
	title    string
}

// Error is both a sentinel - an error definition declared once as a package-level variable with
// New - and an occurrence of it, made by the sentinel's With* methods:
//
//	var ErrInsufficientStock = apperror.New("INSUFFICIENT_STOCK", apperror.CategoryConflict, "Insufficient stock")
//
//	return ErrInsufficientStock // a bare sentinel is a complete error too
//
//	return ErrInsufficientStock.
//		WithInternalMessage("cannot reserve product stock").
//		WithAttrs(slog.Uint64("productID", id), slog.Uint64("available", available)).
//		WithExternalMessage("Not enough stock to reserve the requested items")
//
// The With* methods never change their receiver - a sentinel is shared by every goroutine - but
// return a changed copy, and errors.Is(err, ErrInsufficientStock) matches every copy. An occurrence
// holds what happened (internalMsg and attrs - they never leave the service: logs, err.Error(), etc.),
// what a caller outside the service may be told (externalMsg), what caused
// it, and where it happened (the stack trace, captured by the first With* call on the sentinel).
//
// Give an error a sentinel where it first enters this service's code: a domain rule, or a
// repository turning a gorm error into ErrDatabaseFailure.WithCause(err). An error that is already
// one of ours is returned as it is: wrapping it in another sentinel's WithCause would replace its
// code (and with it the HTTP status and whether it is retried).
//
// Keep errors in variables and results of type error, never *Error: a nil *Error returned as an
// error is not a nil error, so `if err != nil` would be true even when nothing failed.
type Error struct {
	error
	slog.LogValuer

	def         *definition
	internalMsg string
	externalMsg string
	attrs       []slog.Attr
	cause       error
	stack       []uintptr
}

// New declares a sentinel with the code servicePrefix + code: New("INSUFFICIENT_STOCK", ...) is
// SHIPPING.INSUFFICIENT_STOCK. Call it only to initialize a package-level variable. It panics on an
// empty code and on a code that already contains a "." (most likely a prefix written by hand), so
// such a sentinel fails at startup. Nothing checks that two sentinels don't share a code: keep
// codes specific (ORDER_NOT_FOUND, not NOT_FOUND).
func New(code string, category Category, title string) *Error {
	if strings.TrimSpace(code) == "" {
		panic("apperror: empty error code")
	}
	if strings.Contains(code, ".") {
		panic("apperror: error code " + code + ` must not contain "." - New adds the service prefix itself`)
	}

	return &Error{def: &definition{code: servicePrefix + code, category: category, title: title}}
}

// Code is the machine-readable identifier, with the service prefix, e.g.
// SHIPPING.INSUFFICIENT_STOCK.
func (e *Error) Code() string {
	return e.def.code
}

func (e *Error) Category() Category {
	return e.def.category
}

// Title is a short, public, human-readable summary of the sentinel - ProblemDetail.title. It must
// not contain anything specific to one occurrence (ids, amounts, ...): that goes to WithAttrs.
func (e *Error) Title() string {
	return e.def.title
}

// ExternalMessage is the message set by WithExternalMessage, or "" when there is none. It belongs to
// this error only, like its code: a cause's external message describes the cause's code, not this
// one, so it is never used in place of a missing one.
func (e *Error) ExternalMessage() string {
	return e.externalMsg
}

// WithInternalMessage returns a copy with a message that never leaves the service. Keep it
// constant (no values formatted into it), so every occurrence of one failure logs the same message
// and can be grouped; values go to WithAttrs.
func (e *Error) WithInternalMessage(internalMsg string) *Error {
	clone := e.clone()
	clone.internalMsg = internalMsg
	return clone
}

// WithExternalMessage returns a copy with a message about this occurrence that may leave the
// service, e.g. which parameter was invalid. Unlike the title, which is the same for every
// occurrence of the sentinel, it is optional and specific to one occurrence.
func (e *Error) WithExternalMessage(externalMsg string) *Error {
	clone := e.clone()
	clone.externalMsg = externalMsg
	return clone
}

// WithAttrs returns a copy with more data about this occurrence - ids, amounts, balances. Like the
// internal message they never leave the service: they are logged as separate fields.
func (e *Error) WithAttrs(attrs ...slog.Attr) *Error {
	clone := e.clone()
	clone.attrs = append(clone.attrs, attrs...)
	return clone
}

// WithCause returns a copy caused by cause: a library error (gorm, kafka-go, strconv, ...) that gets
// a sentinel here, or - rarely - one of our own errors reported as a different sentinel, e.g. a
// Kafka consumer treating a not-yet-existing entity as transient. The receiver's sentinel wins,
// while errors.Is still finds the cause's own sentinel.
func (e *Error) WithCause(cause error) *Error {
	clone := e.clone()
	clone.cause = cause
	return clone
}

// clone copies e for a With* method, so e itself never changes. The first copy of a sentinel
// captures the stack trace of whoever called the With* method.
func (e *Error) clone() *Error {
	clone := *e
	clone.attrs = slices.Clone(e.attrs)
	if clone.stack == nil {
		clone.stack = captureStack(4) // runtime.Callers, captureStack, clone, the With* method
	}
	return &clone
}

// Is reports whether target is the same sentinel, or another occurrence of it - shallowly, as
// errors.Is expects: errors.Is itself goes on to the cause through Unwrap.
func (e *Error) Is(target error) bool {
	// A type assertion checks target itself only; errors.As would also check target's causes. The
	// errors.Is contract wants Is to compare shallowly and unwrap neither side: errors.Is goes down
	// err's chain itself and calls Is on every node, while target is the sentinel to compare with,
	// not a chain to search. The suppressions keep IDE from "fixing" it into
	// errors.As.
	//goland:noinspection GoTypeAssertionOnErrors
	other, ok := target.(*Error)
	return ok && other.def == e.def
}

func (e *Error) Unwrap() error {
	return e.cause
}

func (e *Error) Error() string {
	parts := make([]string, 0, 3)
	if e.internalMsg != "" {
		parts = append(parts, e.internalMsg)
	}
	parts = append(parts, e.def.code)
	if e.cause != nil {
		parts = append(parts, e.cause.Error())
	}

	return strings.Join(parts, ": ")
}

// OutermostError returns the outermost *Error in err's chain, or ErrInternal when there is none (an error
// that slipped through without a sentinel - see the uncoded log field). It returns nil for a nil
// err.
func OutermostError(err error) *Error {
	if err == nil {
		return nil
	}

	found, _ := outermostError(err)
	return found
}

// outermostError returns the outermost *Error in err's chain and true, or ErrInternal and false when there
// is none.
func outermostError(err error) (*Error, bool) {
	var found *Error
	if errors.As(err, &found) {
		return found, true
	}

	return ErrInternal, false
}

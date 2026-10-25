package apperror

import "fmt"

// FromPanic turns a value recovered from a panic into an ErrInternal occurrence that carries the
// stack trace of the panic. Call it directly in the deferred function that calls recover:
//
//	defer func() {
//		if recovered := recover(); recovered != nil {
//			err = apperror.FromPanic(recovered)
//		}
//	}()
func FromPanic(recovered any) error {
	// %v, not %w: the cause becomes plain text, so a panic is always ErrInternal, even when the
	// panic value was one of our errors.
	return &Error{
		def:         ErrInternal.def,
		internalMsg: "panic",
		cause:       fmt.Errorf("%v", recovered),
		stack:       captureStack(3), // runtime.Callers, captureStack, FromPanic
	}
}

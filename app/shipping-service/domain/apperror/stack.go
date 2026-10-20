package apperror

import (
	"fmt"
	"runtime"
	"slices"
	"strings"
)

const maxStackDepth = 32

// captureStack captures the stack trace, skipping the given number of innermost frames -
// runtime.Callers itself, captureStack and the apperror functions above it - so the trace starts at
// the caller of apperror.
func captureStack(skip int) []uintptr {
	pcs := make([]uintptr, maxStackDepth)
	return pcs[:runtime.Callers(skip, pcs)]
}

// lowestStacks returns, for every failure in err's chain - every error at the end of a branch, with
// no cause of its own - the lowest stack trace on the way down to it, each stack only once. For an
// ordinary chain that is a single stack, the one closest to the failure; an errors.Join adds one per
// joined failure, however deep each of them lies. A failure with no *Error below the join, e.g. a
// gorm error joined in, gets the stack of the nearest *Error above it.
func lowestStacks(err error) [][]uintptr {
	var collectedErrsWithStack []*Error
	collectLowestStacks(err, nil, &collectedErrsWithStack)

	stacks := make([][]uintptr, 0, len(collectedErrsWithStack))
	for _, errWithStack := range collectedErrsWithStack {
		stacks = append(stacks, errWithStack.stack)
	}

	return stacks
}

// collectLowestStacks goes down err's chain carrying inheritedErrWithStack - the lowest *Error with
// a stack seen on the way down, this node included - and at every end of a branch adds it to
// collectedErrsWithStack, unless it is already there. inheritedErrWithStack is passed by value, so
// a lower one found in one branch never reaches its sibling branches; collectedErrsWithStack is a
// pointer, so what every call appends reaches lowestStacks.
func collectLowestStacks(err error, inheritedErrWithStack *Error, collectedErrsWithStack *[]*Error) {
	if err == nil {
		return
	}
	// A type assertion checks this node of the chain only, as in traverse: errors.As would also
	// check its causes, which collectLowestStacks goes down to itself, below.
	//goland:noinspection GoTypeAssertionOnErrors
	if appErr, ok := err.(*Error); ok && appErr.stack != nil {
		inheritedErrWithStack = appErr
	}

	switch unwrapper := err.(type) {
	case interface{ Unwrap() error }:
		if cause := unwrapper.Unwrap(); cause != nil {
			collectLowestStacks(cause, inheritedErrWithStack, collectedErrsWithStack)
			return
		}
	case interface{ Unwrap() []error }:
		if causes := unwrapper.Unwrap(); len(causes) > 0 {
			for _, cause := range causes {
				collectLowestStacks(cause, inheritedErrWithStack, collectedErrsWithStack)
			}
			return
		}
	}

	// The end of a branch: the failure itself.
	if inheritedErrWithStack != nil && !slices.Contains(*collectedErrsWithStack, inheritedErrWithStack) {
		*collectedErrsWithStack = append(*collectedErrsWithStack, inheritedErrWithStack)
	}
}

func formatStack(stack []uintptr) string {
	var builder strings.Builder
	frames := runtime.CallersFrames(stack)
	for {
		frame, more := frames.Next()
		_, _ = fmt.Fprintf(&builder, "%s\n\t%s:%d\n", frame.Function, frame.File, frame.Line)
		if !more {
			break
		}
	}

	return builder.String()
}

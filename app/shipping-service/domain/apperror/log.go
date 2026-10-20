package apperror

import "log/slog"

// LogAttr renders err for slog as one "error" group: code, category, message, uncoded=true when err
// carries no sentinel, the attrs of every *Error in the chain, and - for CategoryInternal only,
// since a stack trace on every 4xx is noise - the stack traces of its failures. Use it instead of
// slog.Any("error", err): slog resolves LogValue only on the value it is given, so an *Error
// wrapped by fmt.Errorf would otherwise be logged as plain text.
func LogAttr(err error) slog.Attr {
	return slog.Attr{Key: "error", Value: logFields(err)}
}

func (e *Error) LogValue() slog.Value {
	return logFields(e)
}

func logFields(err error) slog.Value {
	if err == nil {
		return slog.StringValue("<nil>")
	}

	outermostErr, coded := outermostError(err)
	attrs := []slog.Attr{
		slog.String("code", outermostErr.def.code),
		slog.String("category", outermostErr.def.category.String()),
		slog.String("message", err.Error()),
	}
	if !coded {
		// The error slipped through without a sentinel, so OutermostError fell back to ErrInternal:
		// these records show where a sentinel is missing.
		attrs = append(attrs, slog.Bool("uncoded", true))
	}
	traverse(err, func(e *Error) {
		attrs = append(attrs, e.attrs...)
	})
	if outermostErr.def.category == CategoryInternal {
		attrs = append(attrs, stackAttrs(err)...)
	}

	return slog.GroupValue(attrs...)
}

// traverse calls visit for every *Error in err's chain, depth-first, in the order errors.As would
// find them.
func traverse(err error, visit func(*Error)) {
	if err == nil {
		return
	}
	// A type assertion checks this node of the chain only; errors.As would also check all its
	// causes. traverse goes down to the causes itself, below - with errors.As here, the same *Error
	// would be found again from every node above it, and its attrs logged once per node. The
	// suppressions keep IDE from "fixing" it into errors.As.
	//goland:noinspection GoTypeAssertionOnErrors
	if appErr, ok := err.(*Error); ok {
		visit(appErr)
	}

	switch unwrapper := err.(type) {
	case interface{ Unwrap() error }:
		traverse(unwrapper.Unwrap(), visit)
	case interface{ Unwrap() []error }:
		for _, cause := range unwrapper.Unwrap() {
			traverse(cause, visit)
		}
	}
}

// stackAttrs logs the lowest stacks of err's chain: a single one - the usual case - as "stack", so
// an ordinary record keeps its shape, and several, from an errors.Join, as a "stacks" list.
func stackAttrs(err error) []slog.Attr {
	stacks := lowestStacks(err)
	switch len(stacks) {
	case 0:
		return nil
	case 1:
		return []slog.Attr{slog.String("stack", formatStack(stacks[0]))}
	default:
		texts := make([]string, 0, len(stacks))
		for _, stack := range stacks {
			texts = append(texts, formatStack(stack))
		}
		return []slog.Attr{slog.Any("stacks", texts)}
	}
}

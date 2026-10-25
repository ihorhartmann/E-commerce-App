package apperror

// ErrInternal is the sentinel of every error that carries none of its own (a library error, a
// bug): OutermostError falls back to it.
var ErrInternal = New("INTERNAL_ERROR", CategoryInternal, "Internal server error")

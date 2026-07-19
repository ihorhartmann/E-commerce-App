package errors

// StatusError carries the HTTP status code a specific, already-classified
// error should be reported with, without exposing the underlying error's
// concrete type to the central error handler.
type StatusError struct {
	Status int
	Err    error
}

func (e *StatusError) Error() string {
	return e.Err.Error()
}

func WithStatus(status int, err error) error {
	return &StatusError{Status: status, Err: err}
}

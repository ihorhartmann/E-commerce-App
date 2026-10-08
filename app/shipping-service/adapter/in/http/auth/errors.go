package auth

import "errors"

var (
	ErrInvalidToken = errors.New("invalid access token")
	// ErrMissingPrincipal means a handler that needs the caller ran without AuthMiddleware authenticating one.
	ErrMissingPrincipal = errors.New("request has no authenticated caller")
)

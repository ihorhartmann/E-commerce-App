package middleware

import "errors"

var (
	ErrMissingBearerToken = errors.New("missing Bearer access token")
	ErrAccessDenied       = errors.New("access denied")
)

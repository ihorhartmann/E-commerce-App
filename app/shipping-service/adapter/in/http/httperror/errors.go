package httperror

import "shippingservice/domain/apperror"

// Sentinels of failures the HTTP layer itself detects - parsing, routing, authentication,
// idempotency - rather than the application or the domain. They live here, not next to the
// middleware that returns them, because StatusOf needs them and the middleware imports this package.
var (
	ErrMalformedRequestBody = apperror.New("MALFORMED_REQUEST_BODY", apperror.CategoryInvalid, "Malformed request body")
	ErrInvalidPathParam     = apperror.New("INVALID_PATH_PARAM", apperror.CategoryInvalid, "Invalid path parameter")
	ErrInvalidQueryParam    = apperror.New("INVALID_QUERY_PARAM", apperror.CategoryInvalid, "Invalid query parameter")

	ErrRouteNotFound    = apperror.New("ROUTE_NOT_FOUND", apperror.CategoryNotFound, "No such endpoint")
	ErrMethodNotAllowed = apperror.New("METHOD_NOT_ALLOWED", apperror.CategoryInvalid, "Method not allowed")

	ErrMissingBearerToken = apperror.New("MISSING_BEARER_TOKEN", apperror.CategoryInvalid, "Missing Bearer access token")
	ErrInvalidToken       = apperror.New("INVALID_TOKEN", apperror.CategoryInvalid, "Invalid access token")
	ErrAccessDenied       = apperror.New("ACCESS_DENIED", apperror.CategoryInvalid, "Access denied")

	ErrMissingIdempotencyKey = apperror.New("MISSING_IDEMPOTENCY_KEY", apperror.CategoryInvalid, "Missing Idempotency-Key header")
	ErrIdempotencyKeyReused  = apperror.New("IDEMPOTENCY_KEY_REUSED", apperror.CategoryInvalid, "Idempotency-Key was already used with a different request")
	// ErrRequestInProgress CategoryTransient: repeating the same request once the first one has finished gets its response.
	ErrRequestInProgress = apperror.New("REQUEST_IN_PROGRESS", apperror.CategoryTransient, "A request with this Idempotency-Key is still in progress")
)

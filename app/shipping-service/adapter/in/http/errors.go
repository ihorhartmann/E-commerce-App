package http

import (
	"errors"
	"net/http"
	"shippingservice/application/port/out/repository"
	"shippingservice/application/service"
	"shippingservice/domain/model"

	"github.com/gin-gonic/gin"
)

var (
	ErrInvalidShipmentID  = errors.New("invalid shipment id")
	ErrInvalidPageParam   = errors.New("page and size must be integers")
	ErrInvalidRequestBody = errors.New("malformed request body")
)

func ErrorResponse(message string) gin.H {
	return gin.H{"error": message}
}

// HandleError classifies a known domain/service error into its HTTP status
// by wrapping it in a StatusError and attaching it to the Gin context.
// ErrorHandlerMiddleware resolves the status and writes the response.
// Unrecognized errors are attached unchanged and are treated as unexpected
// by the middleware.
func HandleError(c *gin.Context, err error) {
	switch {
	case errors.Is(err, ErrInvalidShipmentID), errors.Is(err, ErrInvalidPageParam), errors.Is(err, ErrInvalidRequestBody),
		errors.Is(err, service.ErrInvalidPage), errors.Is(err, service.ErrInvalidCancellationReason):
		_ = c.Error(WithStatus(http.StatusBadRequest, err))
	// a courier lost the race to pick the shipment up: a retry would only find it taken
	case errors.Is(err, model.ErrInvalidStatusTransition), errors.Is(err, service.ErrAlreadyPickedUp):
		_ = c.Error(WithStatus(http.StatusConflict, err))
	case errors.Is(err, service.ErrNotAssignedCourier):
		// 403, not 404: couriers may look any shipment up, so its existence is no secret to them
		_ = c.Error(WithStatus(http.StatusForbidden, err))
	case errors.Is(err, repository.ErrShipmentNotFound):
		_ = c.Error(WithStatus(http.StatusNotFound, err))
	case errors.Is(err, repository.ErrConcurrentUpdate):
		// 503, not 409: the conflict is transient - repeating the request reloads the shipment and
		// usually succeeds - unlike a 409 for an invalid transition, which no retry can fix.
		_ = c.Error(WithStatus(http.StatusServiceUnavailable, err))
	default:
		_ = c.Error(err)
	}
}

// StatusError carries the HTTP status code a specific, already-classified
// error should be reported with, without exposing the underlying error's
// concrete type to the central error handler.
type StatusError struct {
	status int
	err    error
}

func (e *StatusError) Error() string {
	return e.err.Error()
}

func (e *StatusError) Status() int {
	return e.status
}

func WithStatus(status int, err error) error {
	return &StatusError{status: status, err: err}
}

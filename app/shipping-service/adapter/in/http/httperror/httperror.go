package httperror

import (
	"log/slog"
	"net/http"
	"strconv"

	"shippingservice/adapter/in/http/httperror/dto"
	"shippingservice/application/service"
	"shippingservice/domain/apperror"

	"github.com/gin-gonic/gin"
)

const (
	contentType = "application/problem+json"
	// typeURNPrefix starts ProblemDetail.type: a URN - a name, not a link - built from the code, so it
	// stays the same wherever the error documentation lives, and always matches the code.
	typeURNPrefix = "urn:ecommerce:error:"
	// retryAfterSeconds is what a 503 (and a request still in progress) asks the caller to wait.
	retryAfterSeconds = 1
)

// Write writes err as a ProblemDetail response and logs it, once: a 5xx as an error with its stack
// trace, anything else as a warning. It is the only place that turns an error into a response - both
// error handlers of the middleware chain call it, so every error response looks the same.
func Write(c *gin.Context, err error) {
	found := apperror.OutermostError(err)
	status := StatusOf(err)

	if status >= http.StatusInternalServerError {
		slog.ErrorContext(c.Request.Context(), "request failed",
			"method", c.Request.Method, "path", c.Request.URL.Path, "status", status, apperror.LogAttr(err))
	} else {
		slog.WarnContext(c.Request.Context(), "http request rejected",
			"method", c.Request.Method, "path", c.Request.URL.Path, "status", status, apperror.LogAttr(err))
	}

	if found.Category().Retryable() {
		c.Header("Retry-After", strconv.Itoa(retryAfterSeconds))
	}

	// gin keeps a Content-Type that is already set, so the problem+json one isn't replaced by c.JSON's
	c.Header("Content-Type", contentType)
	c.JSON(status, toProblemDetail(c, found, status))
}

// StatusOf returns the HTTP status err is reported with: decided by the category of its outermost
// sentinel, except for the sentinels HTTP gives a status of their own (RFC 9110, the IETF
// Idempotency-Key draft).
func StatusOf(err error) int {
	found := apperror.OutermostError(err)
	switch {
	case found.Is(ErrMissingBearerToken), found.Is(ErrInvalidToken):
		return http.StatusUnauthorized
	// 403, not 404: couriers may look any shipment up, so its existence is no secret to them
	case found.Is(ErrAccessDenied), found.Is(service.ErrNotAssignedCourier):
		return http.StatusForbidden
	case found.Is(ErrMethodNotAllowed):
		return http.StatusMethodNotAllowed
	case found.Is(ErrIdempotencyKeyReused):
		return http.StatusUnprocessableEntity
	case found.Is(ErrRequestInProgress):
		return http.StatusConflict
	}

	switch found.Category() {
	case apperror.CategoryInvalid:
		return http.StatusBadRequest
	case apperror.CategoryNotFound:
		return http.StatusNotFound
	case apperror.CategoryConflict:
		return http.StatusConflict
	case apperror.CategoryTransient, apperror.CategoryUnavailable:
		return http.StatusServiceUnavailable
	default:
		return http.StatusInternalServerError
	}
}

func toProblemDetail(c *gin.Context, found *apperror.Error, status int) dto.ProblemDetail {
	return dto.ProblemDetail{
		Type:      typeURNPrefix + found.Code(),
		Title:     found.Title(),
		Status:    status,
		Detail:    found.ExternalMessage(),
		Instance:  c.Request.URL.Path,
		Code:      found.Code(),
		Retryable: found.Category().Retryable(),
	}
}

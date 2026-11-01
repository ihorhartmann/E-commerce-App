package httperror

import (
	"errors"
	"log/slog"
	"net/http"
	"strconv"

	"inventoryservice/domain/apperror"
	"inventoryservice/interfaces/http/httperror/dto"

	"github.com/gofiber/fiber/v2"
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
func Write(c *fiber.Ctx, err error) error {
	err = fromFiberError(err)
	found := apperror.OutermostError(err)
	status := StatusOf(err)

	if status >= http.StatusInternalServerError {
		slog.ErrorContext(c.UserContext(), "request failed",
			"method", c.Method(), "path", c.Path(), "status", status, apperror.LogAttr(err))
	} else {
		slog.WarnContext(c.UserContext(), "http request rejected",
			"method", c.Method(), "path", c.Path(), "status", status, apperror.LogAttr(err))
	}

	if found.Category().Retryable() {
		c.Set(fiber.HeaderRetryAfter, strconv.Itoa(retryAfterSeconds))
	}

	return c.Status(status).JSON(toProblemDetail(c, found, status), contentType)
}

// StatusOf returns the HTTP status err is reported with: decided by the category of its outermost
// sentinel, except for the sentinels HTTP gives a status of their own (RFC 9110, the IETF
// Idempotency-Key draft).
func StatusOf(err error) int {
	found := apperror.OutermostError(fromFiberError(err))
	switch {
	case found.Is(ErrMissingBearerToken), found.Is(ErrInvalidToken):
		return http.StatusUnauthorized
	case found.Is(ErrAccessDenied):
		return http.StatusForbidden
	case found.Is(ErrMethodNotAllowed):
		return http.StatusMethodNotAllowed
	case found.Is(ErrRequestTooLarge):
		return http.StatusRequestEntityTooLarge
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

// fromFiberError gives a sentinel to an error Fiber itself returned - no route for the path, a
// method the route doesn't allow, a body over the limit - which carries only an HTTP status. Any
// other error is returned as it is.
func fromFiberError(err error) error {
	var appErr *apperror.Error
	var fiberErr *fiber.Error
	if errors.As(err, &appErr) || !errors.As(err, &fiberErr) {
		return err
	}

	switch {
	case fiberErr.Code == http.StatusNotFound:
		return ErrRouteNotFound.WithCause(err)
	case fiberErr.Code == http.StatusMethodNotAllowed:
		return ErrMethodNotAllowed.WithCause(err)
	case fiberErr.Code == http.StatusRequestEntityTooLarge:
		return ErrRequestTooLarge.WithCause(err)
	case fiberErr.Code < http.StatusInternalServerError:
		return ErrInvalidRequest.WithAttrs(slog.Int("fiberStatus", fiberErr.Code)).WithCause(err)
	default:
		return apperror.ErrInternal.WithInternalMessage("fiber error").WithCause(err)
	}
}

func toProblemDetail(c *fiber.Ctx, found *apperror.Error, status int) dto.ProblemDetail {
	return dto.ProblemDetail{
		Type:      typeURNPrefix + found.Code(),
		Title:     found.Title(),
		Status:    status,
		Detail:    found.ExternalMessage(),
		Instance:  c.Path(),
		Code:      found.Code(),
		Retryable: found.Category().Retryable(),
	}
}

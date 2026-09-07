package middleware

import (
	"log/slog"
	"net/http"
	"time"

	httperr "inventoryservice/interfaces/http/errors"
	"inventoryservice/interfaces/http/idempotency"
	"inventoryservice/interfaces/http/idempotency/dto"

	"github.com/gofiber/fiber/v2"
)

const (
	idempotencyKeyHeader = "Idempotency-Key"
	// claimTTL is how long an IN_PROGRESS record is honored before it's treated as abandoned
	// (the original request crashed before responding) and reclaimed by a retry.
	claimTTL = 60 * time.Second
)

// IdempotencyMiddleware protects every mutating request (POST/PUT/PATCH/DELETE) with a
// client-supplied Idempotency-Key: a retried request with the same key and body gets the original
// response replayed instead of the handler running again. A request whose handler errors is
// deliberately left IN_PROGRESS rather than cached - it becomes retryable again once claimTTL
// elapses, rather than permanently caching a response we never actually captured.
type IdempotencyMiddleware struct {
	service idempotency.HTTPIdempotencyService
}

func NewIdempotencyMiddleware(service idempotency.HTTPIdempotencyService) *IdempotencyMiddleware {
	return &IdempotencyMiddleware{service: service}
}

func (m *IdempotencyMiddleware) Handle(c *fiber.Ctx) error {
	if !requiresIdempotency(c) {
		return c.Next()
	}

	idempotencyKey := c.Get(idempotencyKeyHeader)
	if idempotencyKey == "" {
		return httperr.WithStatus(http.StatusBadRequest, ErrMissingIdempotencyKey)
	}

	request := dto.RequestFingerprint{
		Method: c.Method(),
		Path:   c.Path(),
		Body:   c.Body(),
	}

	attempt, err := m.service.Claim(c.Context(), idempotencyKey, request, claimTTL)
	if err != nil {
		return err
	}

	switch attempt.Result {
	case dto.Cached:
		c.Response().Header.SetContentType(fiber.MIMEApplicationJSON)
		return c.Status(attempt.Record.HTTPCode).Send(attempt.Record.Response)
	case dto.HashMismatch:
		return httperr.WithStatus(http.StatusConflict, ErrIdempotencyKeyReused)
	case dto.InProgress:
		return httperr.WithStatus(http.StatusConflict, ErrRequestInProgress)
	}

	if err = c.Next(); err != nil {
		return err
	}

	// Best-effort: the handler already produced the correct response above, so a failure here
	// must never override it with an error response - it only means this key's replay cache
	// stays stale (reclaimed like any abandoned IN_PROGRESS record).
	if err = m.service.Complete(c.Context(), idempotencyKey, c.Response().StatusCode(), c.Response().Body()); err != nil {
		slog.Error("failed to record idempotent response", "idempotencyKey", idempotencyKey, "error", err)
	}

	return nil
}

func requiresIdempotency(c *fiber.Ctx) bool {
	switch c.Method() {
	case fiber.MethodPost, fiber.MethodPut, fiber.MethodPatch, fiber.MethodDelete:
		return true
	default:
		return false
	}
}

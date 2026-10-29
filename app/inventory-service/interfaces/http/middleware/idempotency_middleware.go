package middleware

import (
	"log/slog"
	"net/http"
	"time"

	"inventoryservice/domain/apperror"
	"inventoryservice/interfaces/http/httperror"
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
// response replayed instead of the handler running again. A success and a permanent failure are
// cached and replayed alike, since the same request would get the same answer again. Any other
// failure releases the key at once, so a retry with the same key runs the request again.
//
// It has to be registered above HandleControllerError, which writes a controller's error as the
// response before control returns here.
type IdempotencyMiddleware struct {
	service idempotency.HTTPIdempotencyService
}

func NewIdempotencyMiddleware(service idempotency.HTTPIdempotencyService) *IdempotencyMiddleware {
	return &IdempotencyMiddleware{service: service}
}

func (m *IdempotencyMiddleware) Handle(c *fiber.Ctx) error {
	if !requiresIdempotency(c.Method()) {
		return c.Next()
	}

	idempotencyKey := c.Get(idempotencyKeyHeader)
	if idempotencyKey == "" {
		return httperror.ErrMissingIdempotencyKey
	}

	request := dto.RequestFingerprint{
		Method: c.Method(),
		Path:   c.Path(),
		Body:   c.Body(),
	}

	attempt, err := m.service.Claim(c.UserContext(), idempotencyKey, request, claimTTL)
	if err != nil {
		return err
	}

	switch attempt.Result {
	case dto.Cached:
		return m.replay(c, attempt)
	case dto.HashMismatch:
		return httperror.ErrIdempotencyKeyReused.WithAttrs(slog.String("idempotencyKey", idempotencyKey))
	case dto.InProgress:
		return httperror.ErrRequestInProgress.WithAttrs(slog.String("idempotencyKey", idempotencyKey))
	}

	if err = c.Next(); err != nil {
		// Not a controller's error - HandleControllerError below turns every one of those into the
		// response and returns nil (see HandledError below). An error here means the response itself
		// couldn't be written, or HandleControllerError isn't below this middleware: either way there
		// is no response to cache, so the key is released for a retry.
		m.release(c, idempotencyKey)
		return err
	}

	if handledErr := HandledError(c); handledErr != nil && !apperror.OutermostError(handledErr).Category().Permanent() {
		m.release(c, idempotencyKey)
		return nil
	}

	// Best-effort: the handler already produced the correct response above, so a failure here
	// must never override it with an error response - it only means this key's replay cache
	// stays stale (reclaimed like any abandoned IN_PROGRESS record).
	if err = m.service.Complete(c.UserContext(), idempotencyKey, c.Response().StatusCode(), c.Response().Body()); err != nil {
		slog.ErrorContext(c.UserContext(), "failed to record idempotent response",
			slog.String("idempotencyKey", idempotencyKey), apperror.LogAttr(err))
	}

	return nil
}

// replay sends the cached response as it was first sent: an error response keeps its problem+json
// content type, and an empty one stays empty.
func (m *IdempotencyMiddleware) replay(c *fiber.Ctx, attempt *dto.RequestAttempt) error {
	record := attempt.Record
	c.Status(record.HTTPCode)
	if len(record.Response) == 0 {
		return nil
	}

	if record.HTTPCode >= http.StatusBadRequest {
		c.Response().Header.SetContentType("application/problem+json")
	} else {
		c.Response().Header.SetContentType(fiber.MIMEApplicationJSON)
	}
	return c.Send(record.Response)
}

// release is best-effort, like recording a response: if it fails, the key is reclaimed once its
// claim expires, as before.
func (m *IdempotencyMiddleware) release(c *fiber.Ctx, idempotencyKey string) {
	if err := m.service.Release(c.UserContext(), idempotencyKey); err != nil {
		slog.ErrorContext(c.UserContext(), "failed to release idempotency key",
			slog.String("idempotencyKey", idempotencyKey), apperror.LogAttr(err))
	}
}

func requiresIdempotency(method string) bool {
	switch method {
	case fiber.MethodPost, fiber.MethodPut, fiber.MethodPatch, fiber.MethodDelete:
		return true
	default:
		return false
	}
}

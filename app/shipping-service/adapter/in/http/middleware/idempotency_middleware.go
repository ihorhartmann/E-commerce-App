package middleware

import (
	"bytes"
	"io"
	"log/slog"
	"net/http"
	"time"

	inhttp "shippingservice/adapter/in/http"
	"shippingservice/adapter/in/http/idempotency"
	"shippingservice/adapter/in/http/idempotency/dto"

	"github.com/gin-gonic/gin"
)

const (
	idempotencyKeyHeader = "Idempotency-Key"
	// claimTTL is how long an IN_PROGRESS record is honored before it's treated as abandoned (the
	// original request crashed before responding) and reclaimed by a retry.
	claimTTL = 60 * time.Second
)

// responseBodyWriter wraps gin.ResponseWriter to capture the response body, since Gin's default
// writer streams directly to the client and doesn't buffer it anywhere we can read it back from.
type responseBodyWriter struct {
	gin.ResponseWriter
	body *bytes.Buffer
}

func (w *responseBodyWriter) Write(b []byte) (int, error) {
	w.body.Write(b)
	return w.ResponseWriter.Write(b)
}

// IdempotencyMiddleware protects every mutating request (POST/PUT/PATCH/DELETE) with a
// client-supplied Idempotency-Key: a retried request with the same key and body gets the original
// response replayed instead of the handler running again. A request whose handler errors is
// deliberately left IN_PROGRESS rather than cached - it becomes retryable again once claimTTL
// elapses, rather than permanently caching a response we never actually captured. Register this
// after ErrorHandlerMiddleware so the errors it raises are still caught and formatted by it.
type IdempotencyMiddleware struct {
	service idempotency.HTTPIdempotencyService
}

func NewIdempotencyMiddleware(service idempotency.HTTPIdempotencyService) *IdempotencyMiddleware {
	return &IdempotencyMiddleware{service: service}
}

func (m *IdempotencyMiddleware) Handle(c *gin.Context) {
	if !requiresIdempotency(c.Request.Method) {
		c.Next()
		return
	}

	idempotencyKey := c.GetHeader(idempotencyKeyHeader)
	if idempotencyKey == "" {
		_ = c.Error(inhttp.WithStatus(http.StatusBadRequest, ErrMissingIdempotencyKey))
		c.Abort()
		return
	}

	body, err := readAndRestoreBody(c)
	if err != nil {
		_ = c.Error(err)
		c.Abort()
		return
	}

	request := dto.RequestFingerprint{
		Method: c.Request.Method,
		Path:   c.Request.URL.Path,
		Body:   body,
	}

	attempt, err := m.service.Claim(c.Request.Context(), idempotencyKey, request, claimTTL)
	if err != nil {
		_ = c.Error(err)
		c.Abort()
		return
	}

	switch attempt.Result {
	case dto.Cached:
		c.Data(attempt.Record.HTTPCode, "application/json", attempt.Record.Response)
		c.Abort()
		return
	case dto.HashMismatch:
		_ = c.Error(inhttp.WithStatus(http.StatusConflict, ErrIdempotencyKeyReused))
		c.Abort()
		return
	case dto.InProgress:
		_ = c.Error(inhttp.WithStatus(http.StatusConflict, ErrRequestInProgress))
		c.Abort()
		return
	}

	writer := &responseBodyWriter{ResponseWriter: c.Writer, body: &bytes.Buffer{}}
	c.Writer = writer

	c.Next()

	if len(c.Errors) > 0 {
		return
	}

	// Best-effort: the handler already produced the correct response above, so a failure here
	// must never override it with an error response - it only means this key's replay cache
	// stays stale (reclaimed like any abandoned IN_PROGRESS record).
	if err = m.service.Complete(c.Request.Context(), idempotencyKey, writer.Status(), writer.body.Bytes()); err != nil {
		slog.Error("failed to record idempotent response", "idempotencyKey", idempotencyKey, "error", err)
	}
}

func requiresIdempotency(method string) bool {
	switch method {
	case http.MethodPost, http.MethodPut, http.MethodPatch, http.MethodDelete:
		return true
	default:
		return false
	}
}

func readAndRestoreBody(c *gin.Context) ([]byte, error) {
	if c.Request.Body == nil {
		return nil, nil
	}

	body, err := io.ReadAll(c.Request.Body)
	if err != nil {
		return nil, err
	}
	c.Request.Body = io.NopCloser(bytes.NewReader(body))

	return body, nil
}

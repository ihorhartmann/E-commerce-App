package middleware

import (
	"bytes"
	"io"
	"log/slog"
	"net/http"
	"time"

	"shippingservice/adapter/in/http/httperror"
	"shippingservice/adapter/in/http/idempotency"
	"shippingservice/adapter/in/http/idempotency/dto"
	"shippingservice/domain/apperror"

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
// response replayed instead of the handler running again. A success and a permanent failure are
// cached and replayed alike, since the same request would get the same answer again. Any other
// failure releases the key at once, so a retry with the same key runs the request again.
//
// It has to be registered below HandleMiddlewareError, which writes the errors it raises, and above
// HandleControllerError, which writes a controller's error as the response before control returns
// here.
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
		m.abort(c, httperror.ErrMissingIdempotencyKey)
		return
	}

	body, err := readAndRestoreBody(c)
	if err != nil {
		m.abort(c, httperror.ErrMalformedRequestBody.WithInternalMessage("read request body").WithCause(err))
		return
	}

	request := dto.RequestFingerprint{
		Method: c.Request.Method,
		Path:   c.Request.URL.Path,
		Body:   body,
	}

	attempt, err := m.service.Claim(c.Request.Context(), idempotencyKey, request, claimTTL)
	if err != nil {
		m.abort(c, err)
		return
	}

	switch attempt.Result {
	case dto.Cached:
		m.replay(c, attempt)
		c.Abort()
		return
	case dto.HashMismatch:
		m.abort(c, httperror.ErrIdempotencyKeyReused.WithAttrs(slog.String("idempotencyKey", idempotencyKey)))
		return
	case dto.InProgress:
		m.abort(c, httperror.ErrRequestInProgress.WithAttrs(slog.String("idempotencyKey", idempotencyKey)))
		return
	}

	writer := &responseBodyWriter{ResponseWriter: c.Writer, body: &bytes.Buffer{}}
	c.Writer = writer

	c.Next()

	if len(c.Errors) > 0 {
		// Not a controller's error - HandleControllerError below writes every one of those as the
		// response and clears it from the context (see HandledError below). An error still here means
		// HandleControllerError isn't below this middleware: there is no response to cache, so the
		// key is released for a retry.
		m.release(c, idempotencyKey)
		return
	}

	if handledErr := HandledError(c); handledErr != nil && !apperror.OutermostError(handledErr).Category().Permanent() {
		m.release(c, idempotencyKey)
		return
	}

	// Best-effort: the handler already produced the correct response above, so a failure here
	// must never override it with an error response - it only means this key's replay cache
	// stays stale (reclaimed like any abandoned IN_PROGRESS record).
	if err = m.service.Complete(c.Request.Context(), idempotencyKey, writer.Status(), writer.body.Bytes()); err != nil {
		slog.ErrorContext(c.Request.Context(), "failed to record idempotent response",
			slog.String("idempotencyKey", idempotencyKey), apperror.LogAttr(err))
	}
}

// abort leaves err for HandleMiddlewareError and stops the chain.
func (m *IdempotencyMiddleware) abort(c *gin.Context, err error) {
	_ = c.Error(err)
	c.Abort()
}

// replay sends the cached response as it was first sent: an error response keeps its problem+json
// content type, and an empty one stays empty.
func (m *IdempotencyMiddleware) replay(c *gin.Context, attempt *dto.RequestAttempt) {
	record := attempt.Record
	if len(record.Response) == 0 {
		c.Status(record.HTTPCode)
		return
	}

	if record.HTTPCode >= http.StatusBadRequest {
		c.Data(record.HTTPCode, "application/problem+json", record.Response)
	} else {
		c.Data(record.HTTPCode, "application/json", record.Response)
	}
}

// release is best-effort, like recording a response: if it fails, the key is reclaimed once its
// claim expires, as before.
func (m *IdempotencyMiddleware) release(c *gin.Context, idempotencyKey string) {
	if err := m.service.Release(c.Request.Context(), idempotencyKey); err != nil {
		slog.ErrorContext(c.Request.Context(), "failed to release idempotency key",
			slog.String("idempotencyKey", idempotencyKey), apperror.LogAttr(err))
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

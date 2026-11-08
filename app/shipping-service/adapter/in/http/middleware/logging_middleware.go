package middleware

import (
	"log/slog"

	"shippingservice/application/port/common/logging"

	"github.com/gin-gonic/gin"
)

const CorrelationIDHeader = "X-Correlation-Id"

// LoggingMiddleware logs every request on its way in and every successful one on its way out. A
// failed one is logged by httperror.Write instead, where it becomes the response - whether its error
// is still in the context, or HandleControllerError has already written it.
//
// Every line logged while the request is handled carries its saga's correlation id: the one the request brought in its
// header, or the one the service derives as it handles it (logging.ResolveCorrelationID).
func LoggingMiddleware(c *gin.Context) {
	c.Request = c.Request.WithContext(logging.WithCorrelationID(c.Request.Context(), c.GetHeader(CorrelationIDHeader)))

	slog.DebugContext(c.Request.Context(), "received http request", "method", c.Request.Method, "path", c.Request.URL.Path)

	c.Next()

	if len(c.Errors) == 0 && HandledError(c) == nil {
		slog.InfoContext(c.Request.Context(), "successfully handled http request", "method", c.Request.Method, "path", c.Request.URL.Path, "status", c.Writer.Status())
	}
}

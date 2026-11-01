package middleware

import (
	"log/slog"

	"github.com/gin-gonic/gin"
)

// LoggingMiddleware logs every request on its way in and every successful one on its way out. A
// failed one is logged by httperror.Write instead, where it becomes the response - whether its error
// is still in the context, or HandleControllerError has already written it.
func LoggingMiddleware(c *gin.Context) {
	slog.DebugContext(c.Request.Context(), "received http request", "method", c.Request.Method, "path", c.Request.URL.Path)

	c.Next()

	if len(c.Errors) == 0 && HandledError(c) == nil {
		slog.InfoContext(c.Request.Context(), "successfully handled http request", "method", c.Request.Method, "path", c.Request.URL.Path, "status", c.Writer.Status())
	}
}

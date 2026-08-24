package middleware

import (
	"log/slog"

	"github.com/gin-gonic/gin"
)

// LoggingMiddleware centralizes inbound HTTP request logging so individual controllers don't
// each have to log their own receipt/success pairs. Errors are logged separately by
// ErrorHandlerMiddleware.
func LoggingMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		slog.Debug("received http request", "method", c.Request.Method, "path", c.FullPath())

		c.Next()

		if len(c.Errors) == 0 {
			slog.Info("successfully handled http request", "method", c.Request.Method, "path", c.FullPath(), "status", c.Writer.Status())
		}
	}
}

package middleware

import (
	"errors"
	"log/slog"
	"net/http"
	inhttp "shippingservice/adapter/in/http"

	"github.com/gin-gonic/gin"
)

const InternalServerErrorMessage = "Internal server error"

func ErrorHandlerMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Next()

		if len(c.Errors) == 0 {
			return
		}

		err := c.Errors.Last().Err
		status := http.StatusInternalServerError

		var statusErr *inhttp.StatusError
		if errors.As(err, &statusErr) {
			status = statusErr.Status()
		}

		if status >= http.StatusInternalServerError {
			slog.Error("request failed", "err", err, "method", c.Request.Method, "path", c.FullPath())
			c.JSON(status, inhttp.ErrorResponse(InternalServerErrorMessage))
			return
		}

		slog.Warn("request failed", "err", err, "status", status, "method", c.Request.Method, "path", c.FullPath())
		c.JSON(status, inhttp.ErrorResponse(err.Error()))
	}
}

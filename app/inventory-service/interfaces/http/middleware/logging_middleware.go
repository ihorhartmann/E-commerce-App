package middleware

import (
	"log/slog"

	"github.com/gofiber/fiber/v2"
)

// LoggingMiddleware logs every request on its way in and every successful one on its way out. A
// failed one is logged by httperror.Write instead, where it becomes the response - whether it
// comes back here as an error, or as nil after HandleControllerError has already written it.
func LoggingMiddleware(c *fiber.Ctx) error {
	slog.Debug("received http request", "method", c.Method(), "path", c.Path())

	err := c.Next()
	if err == nil && HandledError(c) == nil {
		slog.Info("successfully handled http request", "method", c.Method(), "path", c.Path(), "status", c.Response().StatusCode())
	}

	return err
}

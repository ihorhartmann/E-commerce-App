package middleware

import (
	"log/slog"

	"github.com/gofiber/fiber/v2"
)

func LoggingMiddleware(c *fiber.Ctx) error {
	slog.Debug("received http request", "method", c.Method(), "path", c.Path())

	err := c.Next()
	if err == nil {
		slog.Info("successfully handled http request", "method", c.Method(), "path", c.Path(), "status", c.Response().StatusCode())
	}

	return err
}

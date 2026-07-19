package middleware

import (
	"errors"
	httperr "inventoryservice/interfaceadapters/in/http/errors"
	"log/slog"
	"net/http"

	"github.com/gofiber/fiber/v2"
)

// HandleError is the central Fiber error handler (wired via fiber.Config).
// It logs every error that reaches it and resolves the HTTP status from a
// wrapped errors.StatusError when present, defaulting to 500 otherwise.
func HandleError(c *fiber.Ctx, err error) error {
	status := http.StatusInternalServerError

	var statusErr *httperr.StatusError
	if errors.As(err, &statusErr) {
		status = statusErr.Status
	}

	if status >= http.StatusInternalServerError {
		slog.Error("request failed", "err", err, "method", c.Method(), "path", c.Path())
		return c.Status(status).JSON(httperr.ErrorResponse("Internal server error"))
	}

	slog.Warn("request failed", "err", err, "status", status, "method", c.Method(), "path", c.Path())
	return c.Status(status).JSON(httperr.ErrorResponse(err.Error()))
}

package middleware

import (
	"log/slog"

	"inventoryservice/application/port/common/logging"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/utils"
)

const CorrelationIDHeader = "X-Correlation-Id"

// LoggingMiddleware logs every request on its way in and every successful one on its way out. A
// failed one is logged by httperror.Write instead, where it becomes the response - whether it
// comes back here as an error, or as nil after HandleControllerError has already written it.
//
// Every line logged while the request is handled carries the saga's correlation id the request brought in its header.
func LoggingMiddleware(c *fiber.Ctx) error {
	// copied: Fiber reuses the request's buffers for the next request, and the string must not change under the context
	correlationID := utils.CopyString(c.Get(CorrelationIDHeader))
	c.SetUserContext(logging.WithCorrelationID(c.UserContext(), correlationID))

	slog.DebugContext(c.UserContext(), "received http request", "method", c.Method(), "path", c.Path())

	err := c.Next()
	if err == nil && HandledError(c) == nil {
		slog.InfoContext(c.UserContext(), "successfully handled http request", "method", c.Method(), "path", c.Path(), "status", c.Response().StatusCode())
	}

	return err
}

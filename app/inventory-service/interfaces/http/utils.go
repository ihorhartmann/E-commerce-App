package http

import (
	"log/slog"
	"strconv"

	"inventoryservice/interfaces/http/httperror"

	"github.com/gofiber/fiber/v2"
)

func parseIDParam(c *fiber.Ctx, name string) (uint64, error) {
	raw := c.Params(name)
	value, err := strconv.ParseUint(raw, 10, 64)
	if err != nil || value == 0 {
		return 0, httperror.ErrInvalidPathParam.
			WithAttrs(slog.String("param", name), slog.String("value", raw)).
			WithExternalMessage(name + " must be a positive integer").
			WithCause(err)
	}

	return value, nil
}

func parseIntQuery(c *fiber.Ctx, name string, defaultValue int) (int, error) {
	raw := c.Query(name)
	if raw == "" {
		return defaultValue, nil
	}

	value, err := strconv.Atoi(raw)
	if err != nil {
		return 0, httperror.ErrInvalidQueryParam.
			WithAttrs(slog.String("param", name), slog.String("value", raw)).
			WithExternalMessage(name + " must be an integer").
			WithCause(err)
	}

	return value, nil
}

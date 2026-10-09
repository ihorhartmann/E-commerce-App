package http

import (
	"inventoryservice/domain"
	"strconv"

	"github.com/gofiber/fiber/v2"
)

func parseIDParam(c *fiber.Ctx, name string) (uint64, error) {
	value, err := strconv.ParseUint(c.Params(name), 10, 64)
	if err != nil || value <= 0 {
		return 0, domain.ErrInvalidInput
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
		return 0, domain.ErrInvalidInput
	}

	return value, nil
}

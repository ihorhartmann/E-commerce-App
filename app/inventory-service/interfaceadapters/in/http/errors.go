package http

import (
	"errors"
	"inventoryservice/entities"
	"inventoryservice/usecases/ports/repository"

	"github.com/gofiber/fiber/v2"
)

const (
	ErrInvalidProductID   = "invalid product id"
	ErrInvalidRequestBody = "invalid request body"
)

func errorResponse(message string) fiber.Map {
	return fiber.Map{"error": message}
}

func handleError(c *fiber.Ctx, err error) error {
	switch {
	case errors.Is(err, entities.ErrInvalidInput):
		return c.Status(fiber.StatusBadRequest).JSON(errorResponse(err.Error()))
	case errors.Is(err, repository.ErrProductNotFound):
		return c.Status(fiber.StatusNotFound).JSON(errorResponse(err.Error()))
	default:
		return c.Status(fiber.StatusInternalServerError).JSON(errorResponse("internal server error"))
	}
}

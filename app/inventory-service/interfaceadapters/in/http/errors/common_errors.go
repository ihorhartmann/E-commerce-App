package errors

import (
	"errors"
	"net/http"

	"inventoryservice/entities"

	"github.com/gofiber/fiber/v2"
)

var ErrInvalidRequestBody = errors.New("invalid request body")

func ErrorResponse(message string) fiber.Map {
	return fiber.Map{"error": message}
}

// handleCommonError maps errors that can occur across more than one
// controller to their HTTP status. It reports whether it recognized the
// error so callers can fall back to their own, more specific mapping.
func handleCommonError(err error) (error, bool) {
	switch {
	case errors.Is(err, entities.ErrInvalidInput):
		return WithStatus(http.StatusBadRequest, err), true
	case errors.Is(err, ErrInvalidRequestBody):
		return WithStatus(http.StatusBadRequest, err), true
	default:
		return err, false
	}
}

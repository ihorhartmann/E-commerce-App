package http

import (
	dto "inventoryservice/interfaceadapters/in/http/dto/reservation"
	"inventoryservice/interfaceadapters/in/http/errors"
	"net/http"

	"inventoryservice/usecases"

	"github.com/gofiber/fiber/v2"
)

type ReservationController struct {
	reservationUseCase *usecases.ReservationUseCase
}

func NewReservationController(reservationUseCase *usecases.ReservationUseCase) *ReservationController {
	return &ReservationController{reservationUseCase: reservationUseCase}
}

func (rc *ReservationController) RegisterRoutes(router fiber.Router) {
	rg := router.Group("/reservations")
	rg.Post("/", rc.CreateReservation)
}

func (rc *ReservationController) CreateReservation(c *fiber.Ctx) error {
	var request dto.CreateReservationRequest
	if err := c.BodyParser(&request); err != nil {
		return errors.HandleReservationError(errors.ErrInvalidRequestBody)
	}

	reservationDtos, err := dto.FromCreateReservationRequest(request)
	if err != nil {
		return errors.HandleReservationError(err)
	}

	if _, err = rc.reservationUseCase.CreateForOrder(c.Context(), request.OrderID, reservationDtos); err != nil {
		return errors.HandleReservationError(err)
	}

	return c.SendStatus(http.StatusCreated)
}

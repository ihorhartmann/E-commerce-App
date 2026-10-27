package http

import (
	"inventoryservice/application/port/in"
	dto "inventoryservice/interfaces/http/dto/reservation"
	"inventoryservice/interfaces/http/httperror"
	"net/http"

	"github.com/gofiber/fiber/v2"
)

type ReservationController struct {
	reservationService in.ReservationService
}

func NewReservationController(reservationService in.ReservationService) *ReservationController {
	return &ReservationController{reservationService: reservationService}
}

func (rc *ReservationController) RegisterRoutes(router fiber.Router) {
	rg := router.Group("/reservations")
	rg.Post("/", rc.CreateReservation)
}

func (rc *ReservationController) CreateReservation(c *fiber.Ctx) error {
	var request dto.CreateReservationRequest
	if err := c.BodyParser(&request); err != nil {
		return httperror.ErrMalformedRequestBody.WithInternalMessage("parse reservation request body").WithCause(err)
	}

	reservationDTOs, err := dto.FromCreateReservationRequest(request)
	if err != nil {
		return err
	}

	reservedItems, err := rc.reservationService.ReserveItems(c.UserContext(), request.OrderID, reservationDTOs)
	if err != nil {
		return err
	}

	return c.Status(http.StatusCreated).JSON(dto.ToCreateReservationResponse(reservedItems))
}

package http

import (
	"context"
	"net/http"
	"strconv"

	"shippingservice/adapter/in/http/auth"
	"shippingservice/adapter/in/http/dto"
	portin "shippingservice/application/port/in"
	"shippingservice/domain/model"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// courierRole may look any shipment up; everyone else sees only their own.
const courierRole = "COURIER"

type ShipmentController struct {
	shipmentService portin.ShipmentService
}

func NewShipmentController(shipmentService portin.ShipmentService) *ShipmentController {
	return &ShipmentController{shipmentService: shipmentService}
}

func (sc *ShipmentController) RegisterRoutes(router *gin.RouterGroup) {
	sg := router.Group("/shipments")

	sg.GET("/tracking/:trackingNumber", sc.GetByTrackingNumber)
	sg.POST("/:id/pickup", sc.Pickup)
	sg.POST("/:id/deliver", sc.Deliver)
	sg.POST("/:id/lose", sc.Lose)
	sg.POST("/:id/cancel", sc.Cancel)
	sg.POST("/:id/return", sc.ConfirmReturnedToInventory)
}

// GetByTrackingNumber shows a courier any shipment (they look shipments up to pick them up), and a customer only
// their own - another customer's shipment is reported as not found.
func (sc *ShipmentController) GetByTrackingNumber(c *gin.Context) {
	trackingNumber := c.Param("trackingNumber")
	if trackingNumber == "" {
		HandleError(c, ErrEmptyTrackingNumber)
		return
	}

	principal, ok := c.Value(auth.PrincipalContextKey).(auth.Principal)
	if !ok {
		HandleError(c, auth.ErrMissingPrincipal)
		return
	}

	var shipment *model.Shipment
	var err error
	if principal.HasAnyRole(courierRole) {
		shipment, err = sc.shipmentService.GetByTrackingNumber(c.Request.Context(), trackingNumber)
	} else {
		shipment, err = sc.shipmentService.GetCustomerShipmentByTrackingNumber(c.Request.Context(), trackingNumber, principal.UserID)
	}
	if err != nil {
		HandleError(c, err)
		return
	}

	c.JSON(http.StatusOK, dto.ToShipmentResponse(shipment))
}

// Pickup moves a shipment Created -> InDelivery and assigns it to the calling
// courier.
func (sc *ShipmentController) Pickup(c *gin.Context) {
	sc.handleCourierTransition(c, sc.shipmentService.StartDelivery)
}

// Deliver moves a shipment InDelivery -> Delivered; only the assigned courier may.
func (sc *ShipmentController) Deliver(c *gin.Context) {
	sc.handleCourierTransition(c, sc.shipmentService.Deliver)
}

// Lose moves a shipment InDelivery -> Lost; only the assigned courier may.
func (sc *ShipmentController) Lose(c *gin.Context) {
	sc.handleCourierTransition(c, sc.shipmentService.Lose)
}

// Cancel moves a shipment Created or InDelivery -> Cancelled.
func (sc *ShipmentController) Cancel(c *gin.Context) {
	sc.handleTransition(c, sc.shipmentService.Cancel)
}

// ConfirmReturnedToInventory records that the courier physically brought the goods back
// (Cancelled shipment, holder Courier -> Inventory). Status stays Cancelled. Only the assigned courier may.
func (sc *ShipmentController) ConfirmReturnedToInventory(c *gin.Context) {
	sc.handleCourierTransition(c, sc.shipmentService.ConfirmReturnedToInventory)
}

// handleCourierTransition runs a transition on behalf of the calling courier, taken from the access token.
func (sc *ShipmentController) handleCourierTransition(
	c *gin.Context,
	transition func(ctx context.Context, id uint64, courierID uuid.UUID) (*model.Shipment, error),
) {
	principal, ok := c.Value(auth.PrincipalContextKey).(auth.Principal)
	if !ok {
		HandleError(c, auth.ErrMissingPrincipal)
		return
	}

	sc.handleTransition(c, func(ctx context.Context, id uint64) (*model.Shipment, error) {
		return transition(ctx, id, principal.UserID)
	})
}

func (sc *ShipmentController) handleTransition(c *gin.Context, transition func(ctx context.Context, id uint64) (*model.Shipment, error)) {
	id, err := strconv.ParseUint(c.Param("id"), 10, 64)
	if err != nil || id == 0 {
		HandleError(c, ErrInvalidShipmentID)
		return
	}

	shipment, err := transition(c.Request.Context(), id)
	if err != nil {
		HandleError(c, err)
		return
	}

	c.JSON(http.StatusOK, dto.ToShipmentResponse(shipment))
}

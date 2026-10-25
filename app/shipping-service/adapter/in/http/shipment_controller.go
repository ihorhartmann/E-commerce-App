package http

import (
	"context"
	"net/http"

	"shippingservice/adapter/in/http/auth"
	"shippingservice/adapter/in/http/dto"
	"shippingservice/adapter/in/http/httperror"
	portin "shippingservice/application/port/in"
	"shippingservice/domain/model"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

// defaultPageSize is the page size of a list request that doesn't ask for one.
const defaultPageSize = 20

type ShipmentController struct {
	shipmentService portin.ShipmentService
}

func NewShipmentController(shipmentService portin.ShipmentService) *ShipmentController {
	return &ShipmentController{shipmentService: shipmentService}
}

func (sc *ShipmentController) RegisterRoutes(router *gin.RouterGroup) {
	sg := router.Group("/shipments")

	sg.GET("/available", sc.GetAvailableShipments)
	sg.GET("/mine", sc.GetCourierActiveShipments)
	sg.GET("/:id", sc.GetShipment)
	sg.POST("/:id/pickup", sc.Pickup)
	sg.POST("/:id/deliver", sc.Deliver)
	sg.POST("/:id/lose", sc.Lose)
	sg.POST("/:id/cancel", sc.Cancel)
	sg.POST("/:id/return", sc.ConfirmReturnedToInventory)
}

// GetAvailableShipments lists the shipments any courier may pick up, without their street - it's shown only to the
// courier who picks the shipment up.
func (sc *ShipmentController) GetAvailableShipments(c *gin.Context) {
	page, err := parseIntQuery(c, "page", 0)
	if err != nil {
		_ = c.Error(err)
		return
	}
	size, err := parseIntQuery(c, "size", defaultPageSize)
	if err != nil {
		_ = c.Error(err)
		return
	}

	shipmentPage, err := sc.shipmentService.GetAvailableShipments(c.Request.Context(), page, size)
	if err != nil {
		_ = c.Error(err)
		return
	}

	c.JSON(http.StatusOK, dto.ToShipmentListResponse(shipmentPage, page, size, false))
}

// GetCourierActiveShipments lists the calling courier's shipments they still have to deliver or bring back.
func (sc *ShipmentController) GetCourierActiveShipments(c *gin.Context) {
	principal, ok := c.Value(auth.PrincipalContextKey).(auth.Principal)
	if !ok {
		_ = c.Error(auth.ErrMissingPrincipal)
		return
	}
	page, err := parseIntQuery(c, "page", 0)
	if err != nil {
		_ = c.Error(err)
		return
	}
	size, err := parseIntQuery(c, "size", defaultPageSize)
	if err != nil {
		_ = c.Error(err)
		return
	}

	shipmentPage, err := sc.shipmentService.GetCourierActiveShipments(c.Request.Context(), principal.UserID, page, size)
	if err != nil {
		_ = c.Error(err)
		return
	}

	c.JSON(http.StatusOK, dto.ToShipmentListResponse(shipmentPage, page, size, true))
}

// GetShipment shows any shipment to a courier, with the street only if it's assigned to them.
func (sc *ShipmentController) GetShipment(c *gin.Context) {
	principal, ok := c.Value(auth.PrincipalContextKey).(auth.Principal)
	if !ok {
		_ = c.Error(auth.ErrMissingPrincipal)
		return
	}
	id, err := parseIDParam(c, "id")
	if err != nil {
		_ = c.Error(err)
		return
	}

	shipment, err := sc.shipmentService.GetByID(c.Request.Context(), id)
	if err != nil {
		_ = c.Error(err)
		return
	}

	c.JSON(http.StatusOK, dto.ToShipmentResponse(shipment, shipment.IsAssignedTo(principal.UserID)))
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

// Cancel moves a shipment InDelivery -> Cancelled when the courier couldn't hand the goods over, for the reason they
// give; only the assigned courier may.
func (sc *ShipmentController) Cancel(c *gin.Context) {
	var request dto.CancelShipmentRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		_ = c.Error(httperror.ErrMalformedRequestBody.WithInternalMessage("parse cancel shipment request body").WithCause(err))
		return
	}

	sc.handleCourierTransition(c, func(ctx context.Context, id uint64, courierID uuid.UUID) (*model.Shipment, error) {
		return sc.shipmentService.Cancel(ctx, id, courierID, model.CancellationReason(request.Reason))
	})
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
		_ = c.Error(auth.ErrMissingPrincipal)
		return
	}

	sc.handleTransition(c, func(ctx context.Context, id uint64) (*model.Shipment, error) {
		return transition(ctx, id, principal.UserID)
	})
}

// handleTransition shows the full address: only the assigned courier may move a shipment on.
func (sc *ShipmentController) handleTransition(c *gin.Context, transition func(ctx context.Context, id uint64) (*model.Shipment, error)) {
	id, err := parseIDParam(c, "id")
	if err != nil {
		_ = c.Error(err)
		return
	}

	shipment, err := transition(c.Request.Context(), id)
	if err != nil {
		_ = c.Error(err)
		return
	}

	c.JSON(http.StatusOK, dto.ToShipmentResponse(shipment, true))
}

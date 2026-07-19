package http

import (
	"context"
	"net/http"
	"strconv"

	"shippingservice/adapter/in/http/dto"
	portin "shippingservice/application/port/in"
	"shippingservice/domain/model"

	"github.com/gin-gonic/gin"
)

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
	sg.POST("/:id/return", sc.ReturnToInventory)
}

func (sc *ShipmentController) GetByTrackingNumber(c *gin.Context) {
	trackingNumber := c.Param("trackingNumber")
	if trackingNumber == "" {
		HandleError(c, ErrEmptyTrackingNumber)
		return
	}

	shipment, err := sc.shipmentService.GetByTrackingNumber(c.Request.Context(), trackingNumber)
	if err != nil {
		HandleError(c, err)
		return
	}

	c.JSON(http.StatusOK, dto.ToShipmentResponse(shipment))
}

// Pickup moves a shipment Created -> InDelivery (кур'єр забрав посилку зі складу).
func (sc *ShipmentController) Pickup(c *gin.Context) {
	sc.handleTransition(c, sc.shipmentService.StartDelivery)
}

// Deliver moves a shipment InDelivery -> Delivered.
func (sc *ShipmentController) Deliver(c *gin.Context) {
	sc.handleTransition(c, sc.shipmentService.Deliver)
}

// Lose moves a shipment InDelivery -> Lost (посилку втрачено або пошкоджено в дорозі).
func (sc *ShipmentController) Lose(c *gin.Context) {
	sc.handleTransition(c, sc.shipmentService.Lose)
}

// Cancel moves a shipment Created or InDelivery -> Cancelled.
func (sc *ShipmentController) Cancel(c *gin.Context) {
	sc.handleTransition(c, sc.shipmentService.Cancel)
}

// ReturnToInventory moves a shipment Cancelled -> ReturnedToInventory (кур'єр повернув товар на склад).
func (sc *ShipmentController) ReturnToInventory(c *gin.Context) {
	sc.handleTransition(c, sc.shipmentService.ReturnToInventory)
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

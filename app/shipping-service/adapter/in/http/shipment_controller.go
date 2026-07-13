package http

import (
	"net/http"
	"shippingservice/adapter/in/http/dto"
	portin "shippingservice/application/port/in"

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
}

func (sc *ShipmentController) GetByTrackingNumber(c *gin.Context) {
	trackingNumber := c.Param("trackingNumber")
	if trackingNumber == "" {
		c.JSON(http.StatusBadRequest, errorResponse(ErrEmptyTrackingNumber))
		return
	}

	shipment, err := sc.shipmentService.GetByTrackingNumber(c.Request.Context(), trackingNumber)
	if err != nil {
		handleError(c, err)
		return
	}

	c.JSON(http.StatusOK, dto.ToShipmentResponse(shipment))
}

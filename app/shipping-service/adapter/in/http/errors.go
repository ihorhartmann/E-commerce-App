package http

import (
	"errors"
	"net/http"
	"shippingservice/application/port/out/repository"
	"shippingservice/application/service"

	"github.com/gin-gonic/gin"
)

const ErrEmptyTrackingNumber = "tracking number is required"

func errorResponse(message string) gin.H {
	return gin.H{"error": message}
}

func handleError(c *gin.Context, err error) {
	switch {
	case errors.Is(err, service.ErrInvalidTrackingNumber):
		c.JSON(http.StatusBadRequest, errorResponse(err.Error()))
	case errors.Is(err, repository.ErrShipmentNotFound):
		c.JSON(http.StatusNotFound, errorResponse(err.Error()))
	default:
		c.JSON(http.StatusInternalServerError, errorResponse("internal server error"))
	}
}

package http

import (
	"shippingservice/domain"
	"strconv"

	"github.com/gin-gonic/gin"
)

func parseIDParam(c *gin.Context, name string) (uint64, error) {
	value, err := strconv.ParseUint(c.Param(name), 10, 64)
	if err != nil || value == 0 {
		return 0, domain.ErrInvalidInput
	}

	return value, nil
}

func parseIntQuery(c *gin.Context, name string, defaultValue int) (int, error) {
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

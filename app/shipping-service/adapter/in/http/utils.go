package http

import (
	"log/slog"
	"strconv"

	"shippingservice/adapter/in/http/httperror"

	"github.com/gin-gonic/gin"
)

func parseIDParam(c *gin.Context, name string) (uint64, error) {
	raw := c.Param(name)
	value, err := strconv.ParseUint(raw, 10, 64)
	if err != nil || value == 0 {
		return 0, httperror.ErrInvalidPathParam.
			WithAttrs(slog.String("param", name), slog.String("value", raw)).
			WithExternalMessage(name + " must be a positive integer").
			WithCause(err)
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
		return 0, httperror.ErrInvalidQueryParam.
			WithAttrs(slog.String("param", name), slog.String("value", raw)).
			WithExternalMessage(name + " must be an integer").
			WithCause(err)
	}

	return value, nil
}

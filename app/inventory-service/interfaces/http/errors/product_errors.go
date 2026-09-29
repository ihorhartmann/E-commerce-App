package errors

import (
	"errors"
	"inventoryservice/application/port/out/repository"
	"net/http"

	"inventoryservice/domain/model"
)

var ErrInvalidProductID = errors.New("invalid product id")

// HandleProductError classifies a product-related error into its HTTP
// status by wrapping it in a StatusError. Unrecognized errors are returned
// unchanged and are treated as unexpected by HandleError.
func HandleProductError(err error) error {
	if mapped, ok := handleCommonError(err); ok {
		return mapped
	}

	switch {
	case errors.Is(err, ErrInvalidProductID):
		return WithStatus(http.StatusBadRequest, err)
	case errors.Is(err, model.ErrInsufficientStock), errors.Is(err, model.ErrQuantityBelowReserved), errors.Is(err, model.ErrProductReserved):
		return WithStatus(http.StatusConflict, err)
	case errors.Is(err, repository.ErrProductNotFound):
		return WithStatus(http.StatusNotFound, err)
	default:
		return err
	}
}

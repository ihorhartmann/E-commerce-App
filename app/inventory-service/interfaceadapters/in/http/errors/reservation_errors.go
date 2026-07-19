package errors

import (
	"errors"
	"net/http"

	"inventoryservice/usecases/ports/repository"
)

// HandleReservationError classifies a reservation-related error into its
// HTTP status by wrapping it in a StatusError. Unrecognized errors are
// returned unchanged and are treated as unexpected by HandleError.
func HandleReservationError(err error) error {
	if mapped, ok := handleCommonError(err); ok {
		return mapped
	}

	switch {
	case errors.Is(err, repository.ErrReservationNotFound):
		return WithStatus(http.StatusNotFound, err)
	default:
		return err
	}
}

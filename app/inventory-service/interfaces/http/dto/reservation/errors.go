package reservation

import "inventoryservice/domain/apperror"

var ErrInvalidReservationRequest = apperror.New("INVALID_RESERVATION_REQUEST", apperror.CategoryInvalid, "Invalid reservation request")

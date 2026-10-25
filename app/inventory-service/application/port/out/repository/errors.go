package repository

import "inventoryservice/domain/apperror"

var (
	ErrProductNotFound     = apperror.New("PRODUCT_NOT_FOUND", apperror.CategoryNotFound, "Product not found")
	ErrReservationNotFound = apperror.New("RESERVATION_NOT_FOUND", apperror.CategoryNotFound, "Reservation not found")
	// ErrConcurrentUpdate means the object was changed by someone else since it was loaded, so the
	// update was not applied. Transient: repeating the whole operation reloads it and usually succeeds.
	ErrConcurrentUpdate = apperror.New("CONCURRENT_UPDATE", apperror.CategoryTransient, "The resource was changed concurrently, try again")
)

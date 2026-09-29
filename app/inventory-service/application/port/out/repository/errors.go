package repository

import "errors"

var (
	ErrProductNotFound     = errors.New("product not found")
	ErrReservationNotFound = errors.New("reservation not found")
	// ErrConcurrentUpdate means the object was changed by someone else since it was loaded, so the
	// update was not applied. Transient: repeating the whole operation reloads it and usually succeeds.
	ErrConcurrentUpdate = errors.New("concurrent update")
)

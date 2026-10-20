package model

import "inventoryservice/domain/apperror"

var (
	ErrInvalidReservation = apperror.New("INVALID_RESERVATION", apperror.CategoryInvalid, "Invalid stock reservation")
	// ErrInvalidHolderTransition means the reservation can't be handed over to the requested holder
	// from the holder it currently has.
	ErrInvalidHolderTransition = apperror.New("INVALID_HOLDER_TRANSITION", apperror.CategoryConflict, "Invalid reservation holder transition")
	// ErrReservationNotHeldByCourier means the reserved stock was asked to leave the warehouse
	// before it was handed over to a courier.
	ErrReservationNotHeldByCourier = apperror.New("RESERVATION_NOT_HELD_BY_COURIER", apperror.CategoryConflict, "Reservation is not held by a courier")
	// ErrInconsistentReservation means a stored reservation breaks the reservation's own rules -
	// corrupted data, not a request to reject.
	ErrInconsistentReservation = apperror.New("INCONSISTENT_RESERVATION", apperror.CategoryInternal, "Internal server error")
)

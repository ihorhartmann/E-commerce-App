package model

import "errors"

var (
	// ErrInvalidHolderTransition means the reservation can't be handed over to the requested holder
	// from the holder it currently has.
	ErrInvalidHolderTransition = errors.New("invalid reservation holder transition")
	// ErrReservationNotHeldByCourier means the reserved stock was asked to leave the warehouse
	// before it was handed over to a courier.
	ErrReservationNotHeldByCourier = errors.New("reservation is not held by a courier")
)

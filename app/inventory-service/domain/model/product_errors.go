package model

import "errors"

var (
	ErrInsufficientStock = errors.New("insufficient stock")
	// ErrForeignReservation means a reservation was applied to a product it doesn't reserve stock of.
	ErrForeignReservation = errors.New("reservation belongs to another product")
)

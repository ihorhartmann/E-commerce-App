package service

import "errors"

var (
	ErrNotAssignedCourier = errors.New("shipment is not assigned to this courier")
	ErrAlreadyPickedUp    = errors.New("shipment was already picked up by another courier")
	ErrInvalidPage        = errors.New("invalid page")
	// ErrInvalidCancellationReason means the reason isn't one a courier may cancel a shipment for.
	ErrInvalidCancellationReason = errors.New("invalid cancellation reason")
)

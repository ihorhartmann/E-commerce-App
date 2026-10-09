package service

import "errors"

var (
	ErrNotAssignedCourier = errors.New("shipment is not assigned to this courier")
	ErrAlreadyPickedUp    = errors.New("shipment was already picked up by another courier")
	ErrInvalidPage        = errors.New("invalid page")
)

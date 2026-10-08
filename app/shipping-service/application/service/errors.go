package service

import "errors"

var (
	ErrInvalidTrackingNumber = errors.New("invalid tracking number")
	ErrNotAssignedCourier    = errors.New("shipment is not assigned to this courier")
)

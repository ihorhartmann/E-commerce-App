package service

import "shippingservice/domain/apperror"

var (
	// ErrNotAssignedCourier means a courier tried to change a shipment another courier picked up. It is
	// reported with 403 (see httperror.StatusOf): couriers may look any shipment up, so its existence is
	// no secret to them.
	ErrNotAssignedCourier = apperror.New("NOT_ASSIGNED_COURIER", apperror.CategoryInvalid, "The shipment is not assigned to this courier")
	// ErrAlreadyPickedUp means a courier lost the race to pick the shipment up: a retry would only find
	// it taken.
	ErrAlreadyPickedUp = apperror.New("ALREADY_PICKED_UP", apperror.CategoryConflict, "The shipment was already picked up by another courier")
	ErrInvalidPage     = apperror.New("INVALID_PAGE", apperror.CategoryInvalid, "Invalid page or page size")
	// ErrInvalidCancellationReason means the reason isn't one a courier may cancel a shipment for.
	ErrInvalidCancellationReason = apperror.New("INVALID_CANCELLATION_REASON", apperror.CategoryInvalid, "Invalid cancellation reason")
)

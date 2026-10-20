package model

import "shippingservice/domain/apperror"

var (
	ErrInvalidShipment = apperror.New("INVALID_SHIPMENT", apperror.CategoryInvalid, "Invalid shipment")
	// ErrMissingCourier means a delivery was started without a courier to hand the shipment to.
	ErrMissingCourier          = apperror.New("MISSING_COURIER", apperror.CategoryInvalid, "A courier is required")
	ErrInvalidStatusTransition = apperror.New("INVALID_STATUS_TRANSITION", apperror.CategoryConflict, "Invalid shipment status transition")
	// ErrInconsistentShipment means a shipment's state breaks the shipment's own rules - corrupted
	// stored data, or a transition that should never produce such a state - not a request to reject.
	ErrInconsistentShipment = apperror.New("INCONSISTENT_SHIPMENT", apperror.CategoryInternal, "Internal server error")
)

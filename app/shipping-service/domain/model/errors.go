package model

import "shippingservice/domain/apperror"

var (
	ErrInvalidShipment = apperror.New("INVALID_SHIPMENT", apperror.CategoryInvalid, "Invalid shipment")
	// ErrMissingCourier means a delivery was started without a courier to hand the shipment to.
	ErrMissingCourier          = apperror.New("MISSING_COURIER", apperror.CategoryInvalid, "A courier is required")
	ErrInvalidStatusTransition = apperror.New("INVALID_STATUS_TRANSITION", apperror.CategoryConflict, "Invalid shipment status transition")
	// ErrInconsistentShipment means a stored shipment breaks the shipment's own rules - corrupted data,
	// not a request to reject.
	ErrInconsistentShipment = apperror.New("INCONSISTENT_SHIPMENT", apperror.CategoryInternal, "Internal server error")
	// ErrShipmentInvariantViolated means this service's own code asked the shipment for something no caller
	// should - e.g. a transition into a state no shipment can be in - a bug, not corrupted data and not a request
	// to reject. The internal message says which rule.
	ErrShipmentInvariantViolated = apperror.New("SHIPMENT_INVARIANT_VIOLATED", apperror.CategoryInternal, "Internal server error")
)

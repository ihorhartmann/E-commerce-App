package repository

import "shippingservice/domain/apperror"

var (
	ErrShipmentNotFound = apperror.New("SHIPMENT_NOT_FOUND", apperror.CategoryNotFound, "Shipment not found")
	// ErrShipmentAlreadyExists means the order already has a shipment - an order has at most one.
	ErrShipmentAlreadyExists = apperror.New("SHIPMENT_ALREADY_EXISTS", apperror.CategoryConflict, "The order already has a shipment")
	// ErrConcurrentUpdate means the object was changed by someone else since it was loaded, so the
	// update was not applied. Transient: repeating the whole operation reloads it and usually succeeds.
	ErrConcurrentUpdate = apperror.New("CONCURRENT_UPDATE", apperror.CategoryTransient, "The resource was changed concurrently, try again")
)

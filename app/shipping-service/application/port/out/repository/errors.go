package repository

import "errors"

var (
	ErrShipmentNotFound = errors.New("shipment not found")
	// ErrShipmentAlreadyExists means the order already has a shipment - an order has at most one.
	ErrShipmentAlreadyExists = errors.New("shipment already exists")
	// ErrConcurrentUpdate means the object was changed by someone else since it was loaded, so the
	// update was not applied. Transient: repeating the whole operation reloads it and usually succeeds.
	ErrConcurrentUpdate = errors.New("concurrent update")
)

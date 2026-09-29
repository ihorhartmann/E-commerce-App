package repository

import "errors"

var (
	ErrShipmentNotFound = errors.New("shipment not found")
	// ErrShipmentAlreadyExists means the order already has a shipment - an order has at most one.
	ErrShipmentAlreadyExists = errors.New("shipment already exists")
)

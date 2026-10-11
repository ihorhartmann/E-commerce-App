package model

import "errors"

var (
	ErrInsufficientStock = errors.New("insufficient stock")
	// ErrForeignReservation means a reservation was applied to a product it doesn't reserve stock of.
	ErrForeignReservation       = errors.New("reservation belongs to another product")
	ErrQuantityBelowReserved    = errors.New("quantity below reserved quantity")
	ErrReservedQuantityMismatch = errors.New("reservation exceeds the product's reserved quantity")
	ErrProductReserved          = errors.New("product has reserved stock")
	ErrUnsupportedPriceCurrency = errors.New("product price must be in EUR")
)

package model

import (
	"fmt"
	"slices"
)

type ProductStock struct {
	product      *Product
	reservations []*StockReservation
}

func NewProductStock(product *Product, reservations []*StockReservation) (*ProductStock, error) {
	for _, reservation := range reservations {
		if reservation.ProductID() != product.ID() {
			return nil, fmt.Errorf("%w: reservation %d reserves product %d, not %d", ErrForeignReservation, reservation.ID(), reservation.ProductID(), product.ID())
		}
	}

	return &ProductStock{product: product, reservations: reservations}, nil
}

func (s *ProductStock) Product() *Product {
	return s.product
}

func (s *ProductStock) Reservations() []*StockReservation {
	return s.reservations
}

// Reserved returns the total quantity currently held by the product's stock reservations.
func (s *ProductStock) Reserved() uint {
	var reserved uint
	for _, reservation := range s.reservations {
		reserved += reservation.Quantity()
	}

	return reserved
}

// Available returns the on-hand quantity not held by any stock reservation.
func (s *ProductStock) Available() uint {
	reserved := s.Reserved()
	if reserved > s.product.Quantity() {
		return 0
	}

	return s.product.Quantity() - reserved
}

// CreateReservation validates there is enough available stock and returns a new StockReservation
// for the given order, held for the buyer (the order is placed but not paid yet), or
// ErrInsufficientStock otherwise. It also returns a copy of the stock that already counts the new
// reservation, so reserving the same product again for another line of the same order is checked
// against the stock left after this one.
func (s *ProductStock) CreateReservation(orderID uint64, quantity uint) (*ProductStock, *StockReservation, error) {
	if s.Available() < quantity {
		return nil, nil, ErrInsufficientStock
	}

	reservation, err := NewStockReservation(0, s.product.ID(), orderID, quantity, ReservationHolderBuyer)
	if err != nil {
		return nil, nil, err
	}

	return &ProductStock{product: s.product, reservations: append(slices.Clone(s.reservations), reservation)}, reservation, nil
}

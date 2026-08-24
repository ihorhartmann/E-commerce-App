package model

import (
	"inventoryservice/domain"
	"inventoryservice/domain/values"
	"strings"
)

type Product struct {
	id                uint64
	name              string
	description       string
	price             values.Money
	quantity          uint
	stockReservations []*StockReservation
}

func NewProduct(id uint64, name, description string, price values.Money, quantity uint) (*Product, error) {
	if err := validateProduct(name); err != nil {
		return nil, err
	}

	return &Product{
		id:          id,
		name:        name,
		description: description,
		price:       price,
		quantity:    quantity,
	}, nil
}

func NewExistingProduct(id uint64, name, description string, price values.Money, quantity uint) (*Product, error) {
	if id == 0 {
		return nil, domain.ErrInvalidInput
	}

	return NewProduct(id, name, description, price, quantity)
}

func validateProduct(name string) error {
	if strings.TrimSpace(name) == "" {
		return domain.ErrInvalidInput
	}

	return nil
}

func (p *Product) ID() uint64 {
	return p.id
}

func (p *Product) Name() string {
	return p.name
}

func (p *Product) Description() string {
	return p.description
}

func (p *Product) Price() values.Money {
	return p.price
}

func (p *Product) Quantity() uint {
	return p.quantity
}

func (p *Product) StockReservations() []*StockReservation {
	return p.stockReservations
}

// Reserved returns the total quantity currently held by this product's stock reservations.
func (p *Product) Reserved() uint {
	var reserved uint
	for _, reservation := range p.stockReservations {
		reserved += reservation.Quantity()
	}

	return reserved
}

func (p *Product) Available() uint {
	reserved := p.Reserved()
	if reserved > p.quantity {
		return 0
	}

	return p.quantity - reserved
}

// WithStockReservations returns a copy of the product hydrated with its current stock
// reservations, so Available()/CreateReservation() can be computed against them.
func (p *Product) WithStockReservations(reservations []*StockReservation) *Product {
	return &Product{
		id:                p.id,
		name:              p.name,
		description:       p.description,
		price:             p.price,
		quantity:          p.quantity,
		stockReservations: reservations,
	}
}

// CreateReservation validates there is enough available stock and returns a new
// StockReservation for the given order, or ErrInsufficientStock otherwise. The product must
// be hydrated via WithStockReservations first for Available() to reflect existing reservations.
func (p *Product) CreateReservation(orderID uint64, quantity uint, holder ReservationHolder) (*StockReservation, error) {
	if p.Available() < quantity {
		return nil, ErrInsufficientStock
	}

	return NewStockReservation(0, p.id, orderID, quantity, holder)
}

// Fulfill returns a copy of the product with quantity removed from on-hand stock (the
// reservation physically left the warehouse).
func (p *Product) Fulfill(quantity uint) *Product {
	return &Product{
		id:                p.id,
		name:              p.name,
		description:       p.description,
		price:             p.price,
		quantity:          p.quantity - quantity,
		stockReservations: p.stockReservations,
	}
}

// Restock returns a copy of the product with quantity added back to on-hand stock (the
// courier returned goods that never made it to the buyer).
func (p *Product) Restock(quantity uint) *Product {
	return &Product{
		id:                p.id,
		name:              p.name,
		description:       p.description,
		price:             p.price,
		quantity:          p.quantity + quantity,
		stockReservations: p.stockReservations,
	}
}

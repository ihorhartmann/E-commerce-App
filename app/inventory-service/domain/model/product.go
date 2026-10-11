package model

import (
	"fmt"
	"inventoryservice/domain"
	"inventoryservice/domain/values"
	"strings"
)

type Product struct {
	id          uint64
	name        string
	description string
	price       values.Money
	quantity    uint
	reserved    uint
	version     uint64
}

func NewProduct(id uint64, name, description string, price values.Money, quantity uint) (*Product, error) {
	if err := validateProduct(name, price); err != nil {
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

func NewExistingProduct(id uint64, name, description string, price values.Money, quantity, reserved uint, version uint64) (*Product, error) {
	if id == 0 || reserved > quantity {
		return nil, domain.ErrInvalidInput
	}

	product, err := NewProduct(id, name, description, price, quantity)
	if err != nil {
		return nil, err
	}
	product.reserved = reserved
	product.version = version

	return product, nil
}

// validateProduct lets the catalog sell in EUR only, so every price the order-service gets for an order is in one
// currency. The other currencies Money supports are kept for later.
func validateProduct(name string, price values.Money) error {
	if strings.TrimSpace(name) == "" || !price.Amount().IsPositive() {
		return domain.ErrInvalidInput
	}
	if price.Currency() != values.EUR {
		return fmt.Errorf("%w: got %s", ErrUnsupportedPriceCurrency, price.Currency())
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

func (p *Product) Reserved() uint {
	return p.reserved
}

func (p *Product) Available() uint {
	return p.quantity - p.reserved
}

func (p *Product) Version() uint64 {
	return p.version
}

// Update replaces the product's details. The on-hand quantity can't go below what is already reserved.
func (p *Product) Update(name, description string, price values.Money, quantity uint) error {
	if err := validateProduct(name, price); err != nil {
		return err
	}
	if quantity < p.reserved {
		return fmt.Errorf("%w: product %d has %d reserved, can't set quantity to %d", ErrQuantityBelowReserved, p.id, p.reserved, quantity)
	}

	p.name = name
	p.description = description
	p.price = price
	p.quantity = quantity
	return nil
}

// ValidateDeletion reports whether the product can be deleted: not while any of its stock is
// reserved, since those reservations' orders still count on it.
func (p *Product) ValidateDeletion() error {
	if p.reserved > 0 {
		return fmt.Errorf("%w: product %d has %d reserved", ErrProductReserved, p.id, p.reserved)
	}

	return nil
}

// Reserve validates there is enough available stock, counts quantity as reserved and returns a new
// StockReservation for the given order, held for the buyer (the order is placed but not paid yet).
// Reserving the same product again for another line of the same order is then checked against the
// stock left after this one.
func (p *Product) Reserve(orderID uint64, quantity uint) (*StockReservation, error) {
	if p.Available() < quantity {
		return nil, fmt.Errorf("%w: product %d has %d available, %d requested", ErrInsufficientStock, p.id, p.Available(), quantity)
	}

	reservation, err := NewStockReservation(0, p.id, orderID, quantity, ReservationHolderBuyer)
	if err != nil {
		return nil, err
	}

	p.reserved += quantity
	return reservation, nil
}

// ReleaseReservation stops counting reservation as reserved: the order was cancelled before its goods
// left the warehouse.
func (p *Product) ReleaseReservation(reservation *StockReservation) error {
	if err := p.requireOwnReservation(reservation); err != nil {
		return err
	}

	p.reserved -= reservation.Quantity()
	return nil
}

// Fulfill removes reservation's quantity from both on-hand and reserved stock: the reserved goods
// physically left the warehouse with the courier. It fails if the reservation isn't held by a courier
// yet.
func (p *Product) Fulfill(reservation *StockReservation) error {
	if err := p.requireOwnReservation(reservation); err != nil {
		return err
	}
	if reservation.Holder() != ReservationHolderCourier {
		return fmt.Errorf("%w: reservation %d is held by %s", ErrReservationNotHeldByCourier, reservation.ID(), reservation.Holder())
	}

	p.quantity -= reservation.Quantity()
	p.reserved -= reservation.Quantity()
	return nil
}

// Restock adds quantity back to on-hand stock (the courier returned goods that never made it to the
// buyer).
func (p *Product) Restock(quantity uint) {
	p.quantity += quantity
}

// requireOwnReservation checks reservation reserves this product and is counted in its reserved
// quantity, which also guarantees it's covered by the on-hand quantity (reserved never exceeds it).
func (p *Product) requireOwnReservation(reservation *StockReservation) error {
	if reservation.ProductID() != p.id {
		return fmt.Errorf("%w: reservation %d reserves product %d, not %d", ErrForeignReservation, reservation.ID(), reservation.ProductID(), p.id)
	}
	if reservation.Quantity() > p.reserved {
		return fmt.Errorf("%w: reservation %d holds %d but product %d counts only %d reserved", ErrReservedQuantityMismatch, reservation.ID(), reservation.Quantity(), p.id, p.reserved)
	}

	return nil
}

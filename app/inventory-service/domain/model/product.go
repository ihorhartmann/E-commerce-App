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

// Fulfill returns a copy of the product with reservation's quantity removed from on-hand stock: the
// reserved goods physically left the warehouse with the courier.
func (p *Product) Fulfill(reservation *StockReservation) (*Product, error) {
	if reservation.ProductID() != p.id {
		return nil, fmt.Errorf("%w: reservation %d reserves product %d, not %d", ErrForeignReservation, reservation.ID(), reservation.ProductID(), p.id)
	}
	if reservation.Holder() != ReservationHolderCourier {
		return nil, fmt.Errorf("%w: reservation %d is held by %s", ErrReservationNotHeldByCourier, reservation.ID(), reservation.Holder())
	}
	if reservation.Quantity() > p.quantity {
		return nil, fmt.Errorf("%w: reservation %d needs %d but only %d on hand", ErrInsufficientStock, reservation.ID(), reservation.Quantity(), p.quantity)
	}

	return p.withQuantity(p.quantity - reservation.Quantity()), nil
}

// Restock returns a copy of the product with quantity added back to on-hand stock (the
// courier returned goods that never made it to the buyer).
func (p *Product) Restock(quantity uint) *Product {
	return p.withQuantity(p.quantity + quantity)
}

func (p *Product) withQuantity(quantity uint) *Product {
	return &Product{
		id:          p.id,
		name:        p.name,
		description: p.description,
		price:       p.price,
		quantity:    quantity,
	}
}

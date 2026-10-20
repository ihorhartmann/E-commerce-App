package model

import (
	"log/slog"
	"strings"

	"inventoryservice/domain/values"
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

// NewExistingProduct restores a stored product. Stored data that breaks the product's rules is
// corrupted, so every rule it breaks is reported as ErrInconsistentProduct, not as a request to
// reject.
func NewExistingProduct(id uint64, name, description string, price values.Money, quantity, reserved uint, version uint64) (*Product, error) {
	if id == 0 || reserved > quantity {
		return nil, ErrInconsistentProduct.
			WithInternalMessage("stored product breaks its invariants").
			WithAttrs(slog.Uint64("productID", id), slog.Uint64("quantity", uint64(quantity)), slog.Uint64("reserved", uint64(reserved)))
	}

	product, err := NewProduct(id, name, description, price, quantity)
	if err != nil {
		return nil, ErrInconsistentProduct.
			WithInternalMessage("stored product breaks its invariants").
			WithAttrs(slog.Uint64("productID", id)).
			WithCause(err)
	}
	product.reserved = reserved
	product.version = version

	return product, nil
}

// validateProduct lets the catalog sell in EUR only, so every price the order-service gets for an order is in one
// currency. The other currencies Money supports are kept for later.
func validateProduct(name string, price values.Money) error {
	if strings.TrimSpace(name) == "" {
		return ErrInvalidProductName
	}
	if !price.Amount().IsPositive() {
		return ErrInvalidProductPrice.WithAttrs(slog.String("amount", price.Amount().String()))
	}
	if price.Currency() != values.EUR {
		return ErrUnsupportedPriceCurrency.WithAttrs(slog.String("currency", string(price.Currency())))
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
		return ErrQuantityBelowReserved.
			WithInternalMessage("cannot set quantity below the reserved quantity").
			WithAttrs(slog.Uint64("productID", p.id), slog.Uint64("reserved", uint64(p.reserved)), slog.Uint64("quantity", uint64(quantity)))
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
		return ErrProductReserved.
			WithInternalMessage("cannot delete a product with reserved stock").
			WithAttrs(slog.Uint64("productID", p.id), slog.Uint64("reserved", uint64(p.reserved)))
	}

	return nil
}

// Reserve validates there is enough available stock, counts quantity as reserved and returns a new
// StockReservation for the given order, held for the buyer (the order is placed but not paid yet).
// Reserving the same product again for another line of the same order is then checked against the
// stock left after this one.
func (p *Product) Reserve(orderID uint64, quantity uint) (*StockReservation, error) {
	if p.Available() < quantity {
		return nil, ErrInsufficientStock.
			WithInternalMessage("cannot reserve product stock").
			WithAttrs(
				slog.Uint64("productID", p.id),
				slog.Uint64("orderID", orderID),
				slog.Uint64("available", uint64(p.Available())),
				slog.Uint64("requested", uint64(quantity)),
			)
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
		return ErrReservationNotHeldByCourier.
			WithInternalMessage("cannot fulfill a reservation not held by a courier").
			WithAttrs(slog.Uint64("reservationID", reservation.ID()), slog.String("holder", string(reservation.Holder())))
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
		return ErrForeignReservation.
			WithInternalMessage("reservation reserves another product").
			WithAttrs(slog.Uint64("reservationID", reservation.ID()), slog.Uint64("reservedProductID", reservation.ProductID()), slog.Uint64("productID", p.id))
	}
	if reservation.Quantity() > p.reserved {
		return ErrReservedQuantityMismatch.
			WithInternalMessage("reservation exceeds the product's reserved quantity").
			WithAttrs(
				slog.Uint64("reservationID", reservation.ID()),
				slog.Uint64("reservationQuantity", uint64(reservation.Quantity())),
				slog.Uint64("productID", p.id),
				slog.Uint64("reserved", uint64(p.reserved)),
			)
	}

	return nil
}

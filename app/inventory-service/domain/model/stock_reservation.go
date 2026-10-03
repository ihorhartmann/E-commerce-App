package model

import (
	"fmt"
	"inventoryservice/domain"
)

// ReservationHolder identifies who the reserved stock is currently being held for.
type ReservationHolder string

const (
	// ReservationHolderBuyer means the order has been placed but payment is not yet confirmed.
	ReservationHolderBuyer ReservationHolder = "BUYER"
	// ReservationHolderCourier means payment is confirmed and stock awaits courier pickup.
	ReservationHolderCourier ReservationHolder = "COURIER"
)

func (h ReservationHolder) isValid() bool {
	switch h {
	case ReservationHolderBuyer, ReservationHolderCourier:
		return true
	default:
		return false
	}
}

type StockReservation struct {
	id        uint64
	productId uint64
	orderId   uint64
	quantity  uint
	holder    ReservationHolder
	version   uint64
}

func NewStockReservation(id, productId, orderId uint64, quantity uint, holder ReservationHolder) (*StockReservation, error) {
	if productId == 0 || orderId == 0 || quantity == 0 || !holder.isValid() {
		return nil, domain.ErrInvalidInput
	}

	return &StockReservation{
		id:        id,
		productId: productId,
		orderId:   orderId,
		quantity:  quantity,
		holder:    holder,
	}, nil
}

func NewExistingStockReservation(id, productId, orderId uint64, quantity uint, holder ReservationHolder, version uint64) (*StockReservation, error) {
	if id == 0 {
		return nil, domain.ErrInvalidInput
	}

	reservation, err := NewStockReservation(id, productId, orderId, quantity, holder)
	if err != nil {
		return nil, err
	}
	reservation.version = version

	return reservation, nil
}

func (sr *StockReservation) ID() uint64 {
	return sr.id
}

func (sr *StockReservation) ProductID() uint64 {
	return sr.productId
}

func (sr *StockReservation) OrderID() uint64 {
	return sr.orderId
}

func (sr *StockReservation) Quantity() uint {
	return sr.quantity
}

func (sr *StockReservation) Holder() ReservationHolder {
	return sr.holder
}

func (sr *StockReservation) Version() uint64 {
	return sr.version
}

// HoldByCourier hands the reservation over from the buyer to the courier: the order is paid and its
// stock now awaits courier pickup. Only a reservation still held for the buyer can be handed over.
func (sr *StockReservation) HoldByCourier() error {
	if sr.holder != ReservationHolderBuyer {
		return fmt.Errorf("%w: reservation %d is held by %s, not %s", ErrInvalidHolderTransition, sr.id, sr.holder, ReservationHolderBuyer)
	}

	sr.holder = ReservationHolderCourier
	return nil
}

package model

import "inventoryservice/domain"

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

// UpdateHolder returns a copy of the reservation held by the given holder.
func (sr *StockReservation) UpdateHolder(holder ReservationHolder) (*StockReservation, error) {
	return NewStockReservation(sr.id, sr.productId, sr.orderId, sr.quantity, holder)
}

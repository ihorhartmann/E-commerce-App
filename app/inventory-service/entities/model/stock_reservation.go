package model

import "inventoryservice/entities"

type StockReservation struct {
	id        uint64
	productId uint64
	orderId   uint64
	quantity  uint
}

func NewStockReservation(id, productId, orderId uint64, quantity uint) (*StockReservation, error) {
	if productId == 0 || orderId == 0 || quantity == 0 {
		return nil, entities.ErrInvalidInput
	}

	return &StockReservation{
		id:        id,
		productId: productId,
		orderId:   orderId,
		quantity:  quantity,
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

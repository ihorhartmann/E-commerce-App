package in

import (
	"context"
	"inventoryservice/application/dto"
	"inventoryservice/domain/model"
)

type ReservationService interface {
	ReserveItems(ctx context.Context, orderID uint64, reservationDTOs []dto.ReservationDTO) ([]*model.StockReservation, error)
	Cancel(ctx context.Context, orderID uint64) error
	HoldByCourier(ctx context.Context, orderID uint64) error
	Fulfill(ctx context.Context, orderID uint64) error
}

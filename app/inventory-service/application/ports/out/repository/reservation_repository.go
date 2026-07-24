package repository

import (
	"context"

	"inventoryservice/domain/model"
)

type ReservationRepository interface {
	CreateBatch(ctx context.Context, reservations []*model.StockReservation) ([]*model.StockReservation, error)
	Update(ctx context.Context, reservation *model.StockReservation) (*model.StockReservation, error)
	GetByID(ctx context.Context, reservationID uint64) (*model.StockReservation, error)
	GetByOrderID(ctx context.Context, orderID uint64) ([]*model.StockReservation, error)
	FindByProductIDs(ctx context.Context, productIDs []uint64) ([]*model.StockReservation, error)
	DeleteByIDs(ctx context.Context, reservationIDs []uint64) error
}

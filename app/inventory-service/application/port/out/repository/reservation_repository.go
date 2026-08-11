package repository

import (
	"context"

	"inventoryservice/domain/model"
)

//go:generate go run go.uber.org/mock/mockgen -source=reservation_repository.go -destination=mocks/mock_reservation_repository.go -package=mocks

type ReservationRepository interface {
	CreateBatch(ctx context.Context, reservations []*model.StockReservation) ([]*model.StockReservation, error)
	Update(ctx context.Context, reservation *model.StockReservation) (*model.StockReservation, error)
	GetByID(ctx context.Context, reservationID uint64) (*model.StockReservation, error)
	GetByOrderID(ctx context.Context, orderID uint64) ([]*model.StockReservation, error)
	FindByProductIDs(ctx context.Context, productIDs []uint64) ([]*model.StockReservation, error)
	DeleteByIDs(ctx context.Context, reservationIDs []uint64) error
}

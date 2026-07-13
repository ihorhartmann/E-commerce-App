package repository

import (
	"context"

	"inventoryservice/entities/model"
)

type StockReservationRepository interface {
	Create(ctx context.Context, reservation *model.StockReservation) error
	GetByID(ctx context.Context, reservationID uint64) (*model.StockReservation, error)
	FindByOrderIDAndProductID(ctx context.Context, orderID, productID uint64) (*model.StockReservation, error)
	DeleteByID(ctx context.Context, reservationID uint64) error
}

package repository

import (
	"context"

	"inventoryservice/domain/model"
)

//go:generate go run go.uber.org/mock/mockgen -source=reservation_repository.go -destination=mocks/mock_reservation_repository.go -package=mocks

type ReservationRepository interface {
	CreateBatch(ctx context.Context, reservations []*model.StockReservation) ([]*model.StockReservation, error)
	// Update stores reservation only if it hasn't changed since it was loaded (its version still
	// matches), and returns it as the database now holds it, with the incremented version. It returns
	// ErrConcurrentUpdate if the reservation was changed in between.
	Update(ctx context.Context, reservation *model.StockReservation) (*model.StockReservation, error)
	GetByID(ctx context.Context, reservationID uint64) (*model.StockReservation, error)
	GetByOrderID(ctx context.Context, orderID uint64) ([]*model.StockReservation, error)
	// DeleteAll deletes the reservations only if none of them is gone (ErrReservationNotFound) or
	// changed since it was loaded (ErrConcurrentUpdate). On either error some of them may already be
	// deleted, so it must run inside a transaction that the error rolls back.
	DeleteAll(ctx context.Context, reservations []*model.StockReservation) error
}

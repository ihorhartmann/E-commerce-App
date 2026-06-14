package repository

import (
	"context"
	"inventoryservice/usecases/ports/repository"
	"sync"

	"inventoryservice/entities/model"
)

type inMemoryReservationRepository struct {
	mu                 sync.RWMutex
	reservations       map[uint64]*model.StockReservation
	reservationIDIndex map[reservationKey]uint64
	nextID             uint64
}

type reservationKey struct {
	orderID   uint64
	productID uint64
}

func NewInMemoryReservationRepository() repository.StockReservationRepository {
	return &inMemoryReservationRepository{
		reservations:       make(map[uint64]*model.StockReservation),
		reservationIDIndex: make(map[reservationKey]uint64),
		nextID:             1,
	}
}

func (r *inMemoryReservationRepository) Create(_ context.Context, reservation *model.StockReservation) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	reservationID := reservation.ID()
	if reservationID == 0 {
		reservationID = r.nextID
		r.nextID++
	}

	reservationToSave, err := model.NewStockReservation(
		reservationID,
		reservation.ProductID(),
		reservation.OrderID(),
		reservation.Quantity(),
	)
	if err != nil {
		return err
	}

	key := newReservationKey(reservationToSave.OrderID(), reservationToSave.ProductID())
	r.reservations[reservationToSave.ID()] = reservationToSave
	r.reservationIDIndex[key] = reservationToSave.ID()

	return nil
}

func (r *inMemoryReservationRepository) GetByID(_ context.Context, reservationID uint64) (*model.StockReservation, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	reservation, ok := r.reservations[reservationID]
	if !ok {
		return nil, repository.ErrReservationNotFound
	}

	return reservation, nil
}

func (r *inMemoryReservationRepository) FindByOrderIDAndProductID(
	_ context.Context,
	orderID, productID uint64,
) (*model.StockReservation, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	reservationID, ok := r.reservationIDIndex[newReservationKey(orderID, productID)]
	if !ok {
		return nil, repository.ErrReservationNotFound
	}

	reservation, ok := r.reservations[reservationID]
	if !ok {
		return nil, repository.ErrReservationNotFound
	}

	return reservation, nil
}

func (r *inMemoryReservationRepository) DeleteByID(_ context.Context, reservationID uint64) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	reservation, ok := r.reservations[reservationID]
	if !ok {
		return repository.ErrReservationNotFound
	}

	delete(r.reservations, reservationID)
	delete(r.reservationIDIndex, newReservationKey(reservation.OrderID(), reservation.ProductID()))

	return nil
}

func newReservationKey(orderID, productID uint64) reservationKey {
	return reservationKey{
		orderID:   orderID,
		productID: productID,
	}
}

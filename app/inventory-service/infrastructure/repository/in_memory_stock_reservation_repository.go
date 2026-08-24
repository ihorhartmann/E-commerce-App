package repository

import (
	"context"
	"inventoryservice/application/port/out/repository"
	"sync"

	"inventoryservice/domain/model"
)

type inMemoryReservationRepository struct {
	repository.ReservationRepository
	mu           sync.RWMutex
	reservations map[uint64]*model.StockReservation
	nextID       uint64
}

func NewInMemoryReservationRepository() repository.ReservationRepository {
	return &inMemoryReservationRepository{
		reservations: make(map[uint64]*model.StockReservation),
		nextID:       1,
	}
}

func (r *inMemoryReservationRepository) CreateBatch(_ context.Context, reservations []*model.StockReservation) ([]*model.StockReservation, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	created := make([]*model.StockReservation, 0, len(reservations))
	for _, reservation := range reservations {
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
			reservation.Holder(),
		)
		if err != nil {
			return nil, err
		}

		r.reservations[reservationToSave.ID()] = reservationToSave
		created = append(created, reservationToSave)
	}

	return created, nil
}

func (r *inMemoryReservationRepository) Update(_ context.Context, reservation *model.StockReservation) (*model.StockReservation, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if _, ok := r.reservations[reservation.ID()]; !ok {
		return nil, repository.ErrReservationNotFound
	}

	r.reservations[reservation.ID()] = reservation
	return reservation, nil
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

func (r *inMemoryReservationRepository) FindByProductIDs(_ context.Context, productIDs []uint64) ([]*model.StockReservation, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	idSet := make(map[uint64]struct{}, len(productIDs))
	for _, id := range productIDs {
		idSet[id] = struct{}{}
	}

	found := make([]*model.StockReservation, 0)
	for _, reservation := range r.reservations {
		if _, ok := idSet[reservation.ProductID()]; ok {
			found = append(found, reservation)
		}
	}

	return found, nil
}

func (r *inMemoryReservationRepository) GetByOrderID(_ context.Context, orderID uint64) ([]*model.StockReservation, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	found := make([]*model.StockReservation, 0)
	for _, reservation := range r.reservations {
		if reservation.OrderID() == orderID {
			found = append(found, reservation)
		}
	}

	if len(found) == 0 {
		return nil, repository.ErrReservationNotFound
	}

	return found, nil
}

func (r *inMemoryReservationRepository) DeleteByIDs(_ context.Context, reservationIDs []uint64) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	for _, reservationID := range reservationIDs {
		if _, ok := r.reservations[reservationID]; !ok {
			return repository.ErrReservationNotFound
		}

		delete(r.reservations, reservationID)
	}

	return nil
}

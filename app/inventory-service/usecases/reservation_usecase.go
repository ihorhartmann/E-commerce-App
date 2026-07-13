package usecases

import (
	"context"
	"inventoryservice/usecases/ports/repository"

	"inventoryservice/entities/model"
)

type ReservationUseCase struct {
	reservationRepository repository.StockReservationRepository
}

func NewReservationUseCase(reservationRepository repository.StockReservationRepository) *ReservationUseCase {
	return &ReservationUseCase{reservationRepository: reservationRepository}
}

func (uc *ReservationUseCase) Create(ctx context.Context, reservation *model.StockReservation) error {
	return uc.reservationRepository.Create(ctx, reservation)
}

func (uc *ReservationUseCase) Cancel(ctx context.Context, reservation *model.StockReservation) error {
	existingReservation, err := uc.reservationRepository.FindByOrderIDAndProductID(
		ctx, reservation.OrderID(), reservation.ProductID())
	if err != nil {
		return err
	}

	return uc.reservationRepository.DeleteByID(ctx, existingReservation.ID())
}

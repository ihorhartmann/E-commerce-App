package usecases

import (
	"context"
	"fmt"

	"inventoryservice/entities/model"
	"inventoryservice/usecases/dto"
	"inventoryservice/usecases/ports/repository"
)

type ReservationUseCases struct {
	reservationRepository repository.ReservationRepository
	productRepository     repository.ProductRepository
}

func NewReservationUseCases(
	reservationRepository repository.ReservationRepository,
	productRepository repository.ProductRepository,
) *ReservationUseCases {
	return &ReservationUseCases{
		reservationRepository: reservationRepository,
		productRepository:     productRepository,
	}
}

func (uc *ReservationUseCases) CreateForOrder(
	ctx context.Context,
	orderID uint64,
	reservationDtos []dto.ReservationDto,
) ([]*model.StockReservation, error) {
	productIDs := make([]uint64, 0, len(reservationDtos))
	for _, line := range reservationDtos {
		productIDs = append(productIDs, line.ProductID)
	}

	productsByID, err := uc.loadProducts(ctx, productIDs)
	if err != nil {
		return nil, err
	}

	reservationsByProductID, err := uc.loadReservations(ctx, productIDs)
	if err != nil {
		return nil, err
	}

	reservationsToInsert, err := prepareReservations(orderID, reservationDtos, productsByID, reservationsByProductID)
	if err != nil {
		return nil, err
	}

	return uc.reservationRepository.CreateBatch(ctx, reservationsToInsert)
}

func (uc *ReservationUseCases) loadProducts(ctx context.Context, productIDs []uint64) (map[uint64]*model.Product, error) {
	products, err := uc.productRepository.FindByIDs(ctx, productIDs)
	if err != nil {
		return nil, err
	}

	productsByID := make(map[uint64]*model.Product, len(products))
	for _, product := range products {
		productsByID[product.ID()] = product
	}

	return productsByID, nil
}

func (uc *ReservationUseCases) loadReservations(ctx context.Context, productIDs []uint64) (map[uint64][]*model.StockReservation, error) {
	existingReservations, err := uc.reservationRepository.FindByProductIDs(ctx, productIDs)
	if err != nil {
		return nil, err
	}

	reservationsByProductID := make(map[uint64][]*model.StockReservation, len(existingReservations))
	for _, reservation := range existingReservations {
		reservationsByProductID[reservation.ProductID()] = append(reservationsByProductID[reservation.ProductID()], reservation)
	}

	return reservationsByProductID, nil
}

func prepareReservations(
	orderID uint64,
	reservationDtos []dto.ReservationDto,
	productsByID map[uint64]*model.Product,
	reservationsByProductID map[uint64][]*model.StockReservation,
) ([]*model.StockReservation, error) {
	reservationsToInsert := make([]*model.StockReservation, 0, len(reservationDtos))
	for _, reservationDto := range reservationDtos {
		product, ok := productsByID[reservationDto.ProductID]
		if !ok {
			return nil, fmt.Errorf("%w: product %d", repository.ErrProductNotFound, reservationDto.ProductID)
		}

		reservation, err := product.WithStockReservations(reservationsByProductID[reservationDto.ProductID]).
			CreateReservation(orderID, reservationDto.Quantity, model.ReservationHolderBuyer)
		if err != nil {
			return nil, fmt.Errorf("%w: product %d", err, reservationDto.ProductID)
		}

		reservationsToInsert = append(reservationsToInsert, reservation)
		reservationsByProductID[reservationDto.ProductID] = append(reservationsByProductID[reservationDto.ProductID], reservation)
	}

	return reservationsToInsert, nil
}

// Cancel deletes every stock reservation held for the given order, in one batch.
func (uc *ReservationUseCases) Cancel(ctx context.Context, orderID uint64) error {
	reservations, err := uc.reservationRepository.FindByOrderID(ctx, orderID)
	if err != nil {
		return err
	}

	if len(reservations) == 0 {
		return nil
	}

	ids := make([]uint64, 0, len(reservations))
	for _, reservation := range reservations {
		ids = append(ids, reservation.ID())
	}

	return uc.reservationRepository.DeleteByIDs(ctx, ids)
}

// TODO make it tx and try to bulk update reservations in the future
func (uc *ReservationUseCases) HoldByCourier(ctx context.Context, orderID uint64) error {
	reservations, err := uc.reservationRepository.FindByOrderID(ctx, orderID)
	if err != nil {
		return err
	}

	for _, reservation := range reservations {
		held, err := reservation.WithHolder(model.ReservationHolderCourier)
		if err != nil {
			return err
		}

		if _, err = uc.reservationRepository.Update(ctx, held); err != nil {
			return err
		}
	}

	return nil
}

// TODO make it tx and try to bulk update products in the future
func (uc *ReservationUseCases) Fulfill(ctx context.Context, orderID uint64) error {
	reservations, err := uc.reservationRepository.FindByOrderID(ctx, orderID)
	if err != nil {
		return err
	}

	if len(reservations) == 0 {
		return nil
	}

	ids := make([]uint64, 0, len(reservations))
	for _, reservation := range reservations {
		product, err := uc.productRepository.GetByID(ctx, reservation.ProductID())
		if err != nil {
			return err
		}

		if _, err = uc.productRepository.Update(ctx, product.Fulfill(reservation.Quantity())); err != nil {
			return err
		}

		ids = append(ids, reservation.ID())
	}

	return uc.reservationRepository.DeleteByIDs(ctx, ids)
}

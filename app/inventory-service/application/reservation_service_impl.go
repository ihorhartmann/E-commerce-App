package application

import (
	"context"
	"fmt"
	"inventoryservice/application/ports/in"
	"inventoryservice/application/ports/out/repository"

	"inventoryservice/application/dto"
	"inventoryservice/domain/model"
)

type ReservationServiceImpl struct {
	in.ReservationService
	reservationRepository repository.ReservationRepository
	productRepository     repository.ProductRepository
}

func NewReservationService(
	reservationRepository repository.ReservationRepository,
	productRepository repository.ProductRepository,
) in.ReservationService {
	return &ReservationServiceImpl{
		reservationRepository: reservationRepository,
		productRepository:     productRepository,
	}
}

func (rs *ReservationServiceImpl) ReserveItems(
	ctx context.Context,
	orderID uint64,
	reservationDtos []dto.ReservationDto,
) ([]*model.StockReservation, error) {
	productIDs := make([]uint64, 0, len(reservationDtos))
	for _, line := range reservationDtos {
		productIDs = append(productIDs, line.ProductID)
	}

	productsByID, err := rs.getProducts(ctx, productIDs)
	if err != nil {
		return nil, err
	}

	reservationsByProductID, err := rs.findReservations(ctx, productIDs)
	if err != nil {
		return nil, err
	}

	reservationsToInsert, err := prepareReservations(orderID, reservationDtos, productsByID, reservationsByProductID)
	if err != nil {
		return nil, err
	}

	return rs.reservationRepository.CreateBatch(ctx, reservationsToInsert)
}

func (rs *ReservationServiceImpl) getProducts(ctx context.Context, productIDs []uint64) (map[uint64]*model.Product, error) {
	products, err := rs.productRepository.GetByIDs(ctx, productIDs)
	if err != nil {
		return nil, err
	}

	productsByID := make(map[uint64]*model.Product, len(products))
	for _, product := range products {
		productsByID[product.ID()] = product
	}

	return productsByID, nil
}

func (rs *ReservationServiceImpl) findReservations(ctx context.Context, productIDs []uint64) (map[uint64][]*model.StockReservation, error) {
	existingReservations, err := rs.reservationRepository.FindByProductIDs(ctx, productIDs)
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
		product := productsByID[reservationDto.ProductID]

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
func (rs *ReservationServiceImpl) Cancel(ctx context.Context, orderID uint64) error {
	reservations, err := rs.reservationRepository.GetByOrderID(ctx, orderID)
	if err != nil {
		return err
	}

	ids := make([]uint64, 0, len(reservations))
	for _, reservation := range reservations {
		ids = append(ids, reservation.ID())
	}

	return rs.reservationRepository.DeleteByIDs(ctx, ids)
}

// TODO make it tx and try to bulk update reservations in the future
func (rs *ReservationServiceImpl) HoldByCourier(ctx context.Context, orderID uint64) error {
	reservations, err := rs.reservationRepository.GetByOrderID(ctx, orderID)
	if err != nil {
		return err
	}

	for _, reservation := range reservations {
		held, err := reservation.WithHolder(model.ReservationHolderCourier)
		if err != nil {
			return err
		}

		if _, err = rs.reservationRepository.Update(ctx, held); err != nil {
			return err
		}
	}

	return nil
}

// TODO make it tx and try to bulk update products in the future
func (rs *ReservationServiceImpl) Fulfill(ctx context.Context, orderID uint64) error {
	reservations, err := rs.reservationRepository.GetByOrderID(ctx, orderID)
	if err != nil {
		return err
	}

	ids := make([]uint64, 0, len(reservations))
	for _, reservation := range reservations {
		product, err := rs.productRepository.GetByID(ctx, reservation.ProductID())
		if err != nil {
			return err
		}

		if _, err = rs.productRepository.Update(ctx, product.Fulfill(reservation.Quantity())); err != nil {
			return err
		}

		ids = append(ids, reservation.ID())
	}

	return rs.reservationRepository.DeleteByIDs(ctx, ids)
}

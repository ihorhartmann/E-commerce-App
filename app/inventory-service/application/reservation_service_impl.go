package application

import (
	"context"
	"fmt"
	"inventoryservice/application/port/in"
	"inventoryservice/application/port/out/idempotency"
	idempotencydto "inventoryservice/application/port/out/idempotency/dto"
	"inventoryservice/application/port/out/repository"
	"inventoryservice/application/port/out/repository/transaction"

	"inventoryservice/application/dto"
	"inventoryservice/domain/model"
)

type ReservationServiceImpl struct {
	in.ReservationService
	reservationRepository repository.ReservationRepository
	productRepository     repository.ProductRepository
	processedMessageStore idempotency.ProcessedKafkaMessageStore
	txExecutor            transaction.Executor
}

func NewReservationService(
	reservationRepository repository.ReservationRepository,
	productRepository repository.ProductRepository,
	processedMessageStore idempotency.ProcessedKafkaMessageStore,
	txExecutor transaction.Executor,
) in.ReservationService {
	return &ReservationServiceImpl{
		reservationRepository: reservationRepository,
		productRepository:     productRepository,
		processedMessageStore: processedMessageStore,
		txExecutor:            txExecutor,
	}
}

func (rs *ReservationServiceImpl) ReserveItems(
	ctx context.Context,
	orderID uint64,
	reservationDTOs []dto.ReservationDTO,
) ([]*model.StockReservation, error) {
	productIDs := make([]uint64, 0, len(reservationDTOs))
	for _, line := range reservationDTOs {
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

	reservationsToInsert, err := prepareReservations(orderID, reservationDTOs, productsByID, reservationsByProductID)
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
	reservationDTOs []dto.ReservationDTO,
	productsByID map[uint64]*model.Product,
	reservationsByProductID map[uint64][]*model.StockReservation,
) ([]*model.StockReservation, error) {
	reservationsToInsert := make([]*model.StockReservation, 0, len(reservationDTOs))
	for _, reservationDTO := range reservationDTOs {
		product := productsByID[reservationDTO.ProductID]

		reservation, err := product.WithStockReservations(reservationsByProductID[reservationDTO.ProductID]).
			CreateReservation(orderID, reservationDTO.Quantity, model.ReservationHolderBuyer)
		if err != nil {
			return nil, fmt.Errorf("%w: product %d", err, reservationDTO.ProductID)
		}

		reservationsToInsert = append(reservationsToInsert, reservation)
		reservationsByProductID[reservationDTO.ProductID] = append(reservationsByProductID[reservationDTO.ProductID], reservation)
	}

	return reservationsToInsert, nil
}

// Cancel deletes every stock reservation held for the given order, in one batch.
func (rs *ReservationServiceImpl) Cancel(ctx context.Context, msg idempotencydto.KafkaMessageMetadata, orderID uint64) error {
	return rs.txExecutor.Transaction(ctx, func(ctx context.Context) error {
		alreadyProcessed, err := rs.processedMessageStore.MarkProcessed(ctx, msg)
		if alreadyProcessed || err != nil {
			return err
		}

		reservations, err := rs.reservationRepository.GetByOrderID(ctx, orderID)
		if err != nil {
			return err
		}

		ids := make([]uint64, 0, len(reservations))
		for _, reservation := range reservations {
			ids = append(ids, reservation.ID())
		}

		return rs.reservationRepository.DeleteByIDs(ctx, ids)
	})
}

func (rs *ReservationServiceImpl) HoldByCourier(ctx context.Context, msg idempotencydto.KafkaMessageMetadata, orderID uint64) error {
	return rs.txExecutor.Transaction(ctx, func(ctx context.Context) error {
		alreadyProcessed, err := rs.processedMessageStore.MarkProcessed(ctx, msg)
		if alreadyProcessed || err != nil {
			return err
		}

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
	})
}

func (rs *ReservationServiceImpl) Fulfill(ctx context.Context, msg idempotencydto.KafkaMessageMetadata, orderID uint64) error {
	return rs.txExecutor.Transaction(ctx, func(ctx context.Context) error {
		alreadyProcessed, err := rs.processedMessageStore.MarkProcessed(ctx, msg)
		if alreadyProcessed || err != nil {
			return err
		}

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
	})
}

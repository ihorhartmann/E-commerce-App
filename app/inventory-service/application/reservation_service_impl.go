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
	for _, reservationDTO := range reservationDTOs {
		productIDs = append(productIDs, reservationDTO.ProductID)
	}

	stocks, err := rs.productRepository.GetStocksByIDs(ctx, productIDs)
	if err != nil {
		return nil, err
	}

	stocksByProductID := make(map[uint64]*model.ProductStock, len(stocks))
	for _, stock := range stocks {
		stocksByProductID[stock.Product().ID()] = stock
	}

	reservationsToInsert, err := prepareReservations(orderID, reservationDTOs, stocksByProductID)
	if err != nil {
		return nil, err
	}

	return rs.reservationRepository.CreateBatch(ctx, reservationsToInsert)
}

func prepareReservations(
	orderID uint64,
	reservationDTOs []dto.ReservationDTO,
	stocksByProductID map[uint64]*model.ProductStock,
) ([]*model.StockReservation, error) {
	reservationsToInsert := make([]*model.StockReservation, 0, len(reservationDTOs))
	for _, reservationDTO := range reservationDTOs {
		stock, reservation, err := stocksByProductID[reservationDTO.ProductID].CreateReservation(orderID, reservationDTO.Quantity)
		if err != nil {
			return nil, fmt.Errorf("%w: product %d", err, reservationDTO.ProductID)
		}

		stocksByProductID[reservationDTO.ProductID] = stock
		reservationsToInsert = append(reservationsToInsert, reservation)
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
			held, err := reservation.HoldByCourier()
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

			fulfilled, err := product.Fulfill(reservation)
			if err != nil {
				return err
			}

			if _, err = rs.productRepository.Update(ctx, fulfilled); err != nil {
				return err
			}

			ids = append(ids, reservation.ID())
		}

		return rs.reservationRepository.DeleteByIDs(ctx, ids)
	})
}

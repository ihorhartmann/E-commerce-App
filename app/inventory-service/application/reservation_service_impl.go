package application

import (
	"context"
	"maps"
	"slices"

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

// ReserveItems reserves every line in one transaction: the products' reserved quantities and the new
// reservations are stored together, or not at all.
func (rs *ReservationServiceImpl) ReserveItems(
	ctx context.Context,
	orderID uint64,
	reservationDTOs []dto.ReservationDTO,
) ([]*model.StockReservation, error) {
	var reservations []*model.StockReservation
	err := rs.txExecutor.Transaction(ctx, func(ctx context.Context) error {
		var txErr error
		reservations, txErr = rs.reserveItems(ctx, orderID, reservationDTOs)
		return txErr
	})
	if err != nil {
		return nil, err
	}

	return reservations, nil
}

func (rs *ReservationServiceImpl) reserveItems(
	ctx context.Context,
	orderID uint64,
	reservationDTOs []dto.ReservationDTO,
) ([]*model.StockReservation, error) {
	productIDs := make([]uint64, 0, len(reservationDTOs))
	for _, reservationDTO := range reservationDTOs {
		productIDs = append(productIDs, reservationDTO.ProductID)
	}

	productsByID, err := rs.getProductsByID(ctx, productIDs)
	if err != nil {
		return nil, err
	}

	reservations := make([]*model.StockReservation, 0, len(reservationDTOs))
	for _, reservationDTO := range reservationDTOs {
		reservation, err := productsByID[reservationDTO.ProductID].Reserve(orderID, reservationDTO.Quantity)
		if err != nil {
			return nil, err
		}

		reservations = append(reservations, reservation)
	}

	if err = rs.updateProducts(ctx, productsByID); err != nil {
		return nil, err
	}

	return rs.reservationRepository.CreateBatch(ctx, reservations)
}

// Cancel releases and deletes every stock reservation held for the given order.
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

		productsByID, err := rs.getProductsByID(ctx, reservedProductIDs(reservations))
		if err != nil {
			return err
		}

		for _, reservation := range reservations {
			if err = productsByID[reservation.ProductID()].ReleaseReservation(reservation); err != nil {
				return err
			}
		}

		if err = rs.updateProducts(ctx, productsByID); err != nil {
			return err
		}

		return rs.reservationRepository.DeleteAll(ctx, reservations)
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
			if err = reservation.HoldByCourier(); err != nil {
				return err
			}

			if _, err = rs.reservationRepository.Update(ctx, reservation); err != nil {
				return err
			}
		}

		return nil
	})
}

// Fulfill removes every reservation of the given order from its product's on-hand and reserved
// stock, and deletes the reservations: the goods left the warehouse with the courier.
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

		productsByID, err := rs.getProductsByID(ctx, reservedProductIDs(reservations))
		if err != nil {
			return err
		}

		for _, reservation := range reservations {
			if err = productsByID[reservation.ProductID()].Fulfill(reservation); err != nil {
				return err
			}
		}

		if err = rs.updateProducts(ctx, productsByID); err != nil {
			return err
		}

		return rs.reservationRepository.DeleteAll(ctx, reservations)
	})
}

func (rs *ReservationServiceImpl) getProductsByID(ctx context.Context, productIDs []uint64) (map[uint64]*model.Product, error) {
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

func (rs *ReservationServiceImpl) updateProducts(ctx context.Context, productsByID map[uint64]*model.Product) error {
	for _, id := range slices.Sorted(maps.Keys(productsByID)) {
		if _, err := rs.productRepository.Update(ctx, productsByID[id]); err != nil {
			return err
		}
	}

	return nil
}

func reservedProductIDs(reservations []*model.StockReservation) []uint64 {
	ids := make([]uint64, 0, len(reservations))
	for _, reservation := range reservations {
		ids = append(ids, reservation.ProductID())
	}

	return ids
}

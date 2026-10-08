package service

import (
	"context"
	"fmt"
	"strings"

	commonkafka "shippingservice/application/port/common/kafka"
	portin "shippingservice/application/port/in"
	"shippingservice/application/port/out/idempotency"
	idempotencydto "shippingservice/application/port/out/idempotency/dto"
	portoutkafka "shippingservice/application/port/out/kafka"
	"shippingservice/application/port/out/repository"
	"shippingservice/application/port/out/repository/transaction"
	"shippingservice/domain/model"
	"shippingservice/domain/value"

	"github.com/google/uuid"
)

type ShipmentServiceImpl struct {
	portin.ShipmentService
	shipmentRepository    repository.ShipmentRepository
	shipmentProducer      portoutkafka.ShipmentKafkaProducer
	processedMessageStore idempotency.ProcessedKafkaMessageStore
	txExecutor            transaction.Executor
}

func NewShipmentServiceImpl(
	shipmentRepository repository.ShipmentRepository,
	shipmentProducer portoutkafka.ShipmentKafkaProducer,
	processedMessageStore idempotency.ProcessedKafkaMessageStore,
	txExecutor transaction.Executor,
) portin.ShipmentService {
	return &ShipmentServiceImpl{
		shipmentRepository:    shipmentRepository,
		shipmentProducer:      shipmentProducer,
		processedMessageStore: processedMessageStore,
		txExecutor:            txExecutor,
	}
}

func (s *ShipmentServiceImpl) GetByTrackingNumber(ctx context.Context, trackingNumber string) (*model.Shipment, error) {
	if strings.TrimSpace(trackingNumber) == "" {
		return nil, ErrInvalidTrackingNumber
	}

	return s.shipmentRepository.GetByTrackingNumber(ctx, trackingNumber)
}

func (s *ShipmentServiceImpl) GetCustomerShipmentByTrackingNumber(
	ctx context.Context,
	trackingNumber string,
	customerID uuid.UUID,
) (*model.Shipment, error) {
	shipment, err := s.GetByTrackingNumber(ctx, trackingNumber)
	if err != nil {
		return nil, err
	}

	// "not found" rather than "forbidden": another customer's shipment must not even be confirmed to exist
	if !shipment.IsOwnedBy(customerID) {
		return nil, repository.ErrShipmentNotFound
	}

	return shipment, nil
}

func (s *ShipmentServiceImpl) CreateShipment(ctx context.Context, msg idempotencydto.KafkaMessageMetadata, orderID uint64, customerID uuid.UUID, address value.Address) (*model.Shipment, error) {
	return transaction.Execute(ctx, s.txExecutor, func(ctx context.Context) (*model.Shipment, error) {
		alreadyProcessed, err := s.processedMessageStore.MarkProcessed(ctx, msg)
		if alreadyProcessed || err != nil {
			return nil, err
		}

		shipment, err := model.NewShipment(orderID, customerID, address)
		if err != nil {
			return nil, err
		}

		shipment, err = s.shipmentRepository.Create(ctx, shipment)
		if err != nil {
			return nil, err
		}

		if err = s.shipmentProducer.PublishShipmentStatusChanged(ctx, msg.CorrelationID, shipment); err != nil {
			return nil, err
		}

		return shipment, nil
	})
}

func (s *ShipmentServiceImpl) StartDelivery(ctx context.Context, id uint64, courierID uuid.UUID) (*model.Shipment, error) {
	return s.applyTransition(ctx, id, func(shipment *model.Shipment) error { return shipment.StartDelivery(courierID) })
}

func (s *ShipmentServiceImpl) Deliver(ctx context.Context, id uint64, courierID uuid.UUID) (*model.Shipment, error) {
	return s.applyTransition(ctx, id, func(shipment *model.Shipment) error {
		return requireAssignedCourier(shipment, courierID, (*model.Shipment).Deliver)
	})
}

func (s *ShipmentServiceImpl) Lose(ctx context.Context, id uint64, courierID uuid.UUID) (*model.Shipment, error) {
	return s.applyTransition(ctx, id, func(shipment *model.Shipment) error {
		return requireAssignedCourier(shipment, courierID, (*model.Shipment).Lose)
	})
}

func (s *ShipmentServiceImpl) Cancel(ctx context.Context, id uint64) (*model.Shipment, error) {
	return s.applyTransition(ctx, id, (*model.Shipment).Cancel)
}

// ConfirmReturnedToInventory only changes the shipment's holder, not its status, so it publishes
// PublishShipmentReturnedToInventory directly instead of going through applyTransition (which
// always re-publishes the current status via PublishShipmentStatusChanged). It's triggered over
// REST, with no incoming saga correlationID to reuse - the orderId is the only available
// correlation point, so it's derived here instead.
func (s *ShipmentServiceImpl) ConfirmReturnedToInventory(ctx context.Context, id uint64, courierID uuid.UUID) (*model.Shipment, error) {
	return transaction.Execute(ctx, s.txExecutor, func(ctx context.Context) (*model.Shipment, error) {
		shipment, err := s.shipmentRepository.GetByID(ctx, id)
		if err != nil {
			return nil, err
		}

		if err = requireAssignedCourier(shipment, courierID, (*model.Shipment).ConfirmReturnedToInventory); err != nil {
			return nil, err
		}

		shipment, err = s.shipmentRepository.Update(ctx, shipment)
		if err != nil {
			return nil, err
		}

		correlationID := commonkafka.FormatKafkaId(shipment.OrderId())
		if err = s.shipmentProducer.PublishShipmentReturnedToInventory(ctx, correlationID, shipment); err != nil {
			return nil, err
		}

		return shipment, nil
	})
}

// applyTransition is triggered over REST, with no incoming saga correlationID to reuse - the
// orderId is the only available correlation point, so it's derived here instead.
func (s *ShipmentServiceImpl) applyTransition(
	ctx context.Context,
	id uint64,
	transition func(*model.Shipment) error,
) (*model.Shipment, error) {
	return transaction.Execute(ctx, s.txExecutor, func(ctx context.Context) (*model.Shipment, error) {
		shipment, err := s.shipmentRepository.GetByID(ctx, id)
		if err != nil {
			return nil, err
		}

		if err = transition(shipment); err != nil {
			return nil, err
		}

		shipment, err = s.shipmentRepository.Update(ctx, shipment)
		if err != nil {
			return nil, err
		}

		correlationID := commonkafka.FormatKafkaId(shipment.OrderId())
		if err = s.shipmentProducer.PublishShipmentStatusChanged(ctx, correlationID, shipment); err != nil {
			return nil, err
		}

		return shipment, nil
	})
}

// requireAssignedCourier runs a transition only for the courier who picked the shipment up - nobody else may deliver
// it, report it lost or bring it back.
func requireAssignedCourier(shipment *model.Shipment, courierID uuid.UUID, transition func(*model.Shipment) error) error {
	if !shipment.IsAssignedTo(courierID) {
		return fmt.Errorf("%w: shipment %d", ErrNotAssignedCourier, shipment.Id())
	}

	return transition(shipment)
}

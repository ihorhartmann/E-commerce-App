package service

import (
	"context"
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

func (s *ShipmentServiceImpl) CreateShipment(ctx context.Context, msg idempotencydto.KafkaMessageMetadata, orderID uint64, address value.Address) (*model.Shipment, error) {
	return transaction.Execute(ctx, s.txExecutor, func(ctx context.Context) (*model.Shipment, error) {
		alreadyProcessed, err := s.processedMessageStore.MarkProcessed(ctx, msg)
		if alreadyProcessed || err != nil {
			return nil, err
		}

		shipment, err := model.NewShipment(orderID, address)
		if err != nil {
			return nil, err
		}

		created, err := s.shipmentRepository.Create(ctx, shipment)
		if err != nil {
			return nil, err
		}

		if err = s.shipmentProducer.PublishShipmentStatusChanged(ctx, msg.CorrelationID, created); err != nil {
			return nil, err
		}

		return created, nil
	})
}

func (s *ShipmentServiceImpl) StartDelivery(ctx context.Context, id uint64) (*model.Shipment, error) {
	return s.applyTransition(ctx, id, (*model.Shipment).StartDelivery)
}

func (s *ShipmentServiceImpl) Deliver(ctx context.Context, id uint64) (*model.Shipment, error) {
	return s.applyTransition(ctx, id, (*model.Shipment).Deliver)
}

func (s *ShipmentServiceImpl) Lose(ctx context.Context, id uint64) (*model.Shipment, error) {
	return s.applyTransition(ctx, id, (*model.Shipment).Lose)
}

func (s *ShipmentServiceImpl) Cancel(ctx context.Context, id uint64) (*model.Shipment, error) {
	return s.applyTransition(ctx, id, (*model.Shipment).Cancel)
}

// ConfirmReturnedToInventory only changes the shipment's holder, not its status, so it publishes
// PublishShipmentReturnedToInventory directly instead of going through applyTransition (which
// always re-publishes the current status via PublishShipmentStatusChanged). It's triggered over
// REST, with no incoming saga correlationID to reuse - the orderId is the only available
// correlation point, so it's derived here instead.
func (s *ShipmentServiceImpl) ConfirmReturnedToInventory(ctx context.Context, id uint64) (*model.Shipment, error) {
	return transaction.Execute(ctx, s.txExecutor, func(ctx context.Context) (*model.Shipment, error) {
		shipment, err := s.shipmentRepository.GetByID(ctx, id)
		if err != nil {
			return nil, err
		}

		transitioned, err := shipment.ConfirmReturnedToInventory()
		if err != nil {
			return nil, err
		}

		updated, err := s.shipmentRepository.Update(ctx, transitioned)
		if err != nil {
			return nil, err
		}

		correlationID := commonkafka.FormatKafkaId(updated.OrderId())
		if err = s.shipmentProducer.PublishShipmentReturnedToInventory(ctx, correlationID, updated); err != nil {
			return nil, err
		}

		return updated, nil
	})
}

// applyTransition is triggered over REST, with no incoming saga correlationID to reuse - the
// orderId is the only available correlation point, so it's derived here instead.
func (s *ShipmentServiceImpl) applyTransition(
	ctx context.Context,
	id uint64,
	transition func(*model.Shipment) (*model.Shipment, error),
) (*model.Shipment, error) {
	return transaction.Execute(ctx, s.txExecutor, func(ctx context.Context) (*model.Shipment, error) {
		shipment, err := s.shipmentRepository.GetByID(ctx, id)
		if err != nil {
			return nil, err
		}

		transitioned, err := transition(shipment)
		if err != nil {
			return nil, err
		}

		updated, err := s.shipmentRepository.Update(ctx, transitioned)
		if err != nil {
			return nil, err
		}

		correlationID := commonkafka.FormatKafkaId(updated.OrderId())
		if err = s.shipmentProducer.PublishShipmentStatusChanged(ctx, correlationID, updated); err != nil {
			return nil, err
		}

		return updated, nil
	})
}

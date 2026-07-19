package service

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"strings"

	portin "shippingservice/application/port/in"
	portoutkafka "shippingservice/application/port/out/kafka"
	"shippingservice/application/port/out/repository"
	"shippingservice/domain/model"
	"shippingservice/domain/value"

	"github.com/google/uuid"
)

type ShipmentServiceImpl struct {
	portin.ShipmentService
	shipmentRepository repository.ShipmentRepository
	shipmentProducer   portoutkafka.ShipmentProducer
}

func NewShipmentServiceImpl(
	shipmentRepository repository.ShipmentRepository,
	shipmentProducer portoutkafka.ShipmentProducer,
) *ShipmentServiceImpl {
	return &ShipmentServiceImpl{
		shipmentRepository: shipmentRepository,
		shipmentProducer:   shipmentProducer,
	}
}

func (s *ShipmentServiceImpl) GetByTrackingNumber(ctx context.Context, trackingNumber string) (*model.Shipment, error) {
	if strings.TrimSpace(trackingNumber) == "" {
		return nil, ErrInvalidTrackingNumber
	}

	return s.shipmentRepository.GetByTrackingNumber(ctx, trackingNumber)
}

// CreateShipment is triggered by the CreateShipment Kafka command, so correlationID is the one
// carried by that command - it already identifies the same saga instance as the rest of the
// order/payment/shipping event chain.
func (s *ShipmentServiceImpl) CreateShipment(ctx context.Context, correlationID string, orderID uint64, address value.Address) (*model.Shipment, error) {
	trackingNumber := fmt.Sprintf("TRK-%d-%s", orderID, strings.ToUpper(uuid.New().String()[:8]))
	shipment := model.NewShipment(orderID, trackingNumber, address)

	created, err := s.shipmentRepository.Create(ctx, shipment)
	if err != nil {
		if pubErr := s.shipmentProducer.PublishShipmentCreationFailed(ctx, correlationID, orderID); pubErr != nil {
			return nil, errors.Join(err, pubErr)
		}
		return nil, err
	}

	if err = s.shipmentProducer.PublishShipmentStatusChanged(ctx, correlationID, created); err != nil {
		return nil, err
	}

	return created, nil
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

func (s *ShipmentServiceImpl) ReturnToInventory(ctx context.Context, id uint64) (*model.Shipment, error) {
	return s.applyTransition(ctx, id, (*model.Shipment).ReturnToInventory)
}

// applyTransition is triggered over REST, with no incoming saga correlationID to reuse - the
// orderId is the only available correlation point, so it's derived here instead.
func (s *ShipmentServiceImpl) applyTransition(
	ctx context.Context,
	id uint64,
	transition func(*model.Shipment) (*model.Shipment, error),
) (*model.Shipment, error) {
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

	correlationID := strconv.FormatUint(updated.OrderId(), 10)
	if err = s.shipmentProducer.PublishShipmentStatusChanged(ctx, correlationID, updated); err != nil {
		return nil, err
	}

	return updated, nil
}

package service

import (
	"context"
	portin "shippingservice/application/port/in"
	"shippingservice/application/port/out/repository"
	"shippingservice/domain/model"
	"strings"
)

type ShipmentServiceImpl struct {
	portin.ShipmentService
	shipmentRepository repository.ShipmentRepository
}

func NewShipmentServiceImpl(shipmentRepository repository.ShipmentRepository) *ShipmentServiceImpl {
	return &ShipmentServiceImpl{shipmentRepository: shipmentRepository}
}

func (s *ShipmentServiceImpl) GetByTrackingNumber(ctx context.Context, trackingNumber string) (*model.Shipment, error) {
	if strings.TrimSpace(trackingNumber) == "" {
		return nil, ErrInvalidTrackingNumber
	}

	return s.shipmentRepository.GetByTrackingNumber(ctx, trackingNumber)
}

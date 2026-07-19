package repository

import (
	"context"
	"shippingservice/domain/model"
)

type ShipmentRepository interface {
	Create(ctx context.Context, shipment *model.Shipment) (*model.Shipment, error)
	GetByID(ctx context.Context, id uint64) (*model.Shipment, error)
	GetByTrackingNumber(ctx context.Context, trackingNumber string) (*model.Shipment, error)
	Update(ctx context.Context, shipment *model.Shipment) (*model.Shipment, error)
}

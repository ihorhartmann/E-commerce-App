package repository

import (
	"context"
	"shippingservice/domain/model"
)

type ShipmentRepository interface {
	GetByTrackingNumber(ctx context.Context, trackingNumber string) (*model.Shipment, error)
}

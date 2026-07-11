package in

import (
	"context"
	"shippingservice/domain/model"
)

type ShipmentService interface {
	GetByTrackingNumber(ctx context.Context, trackingNumber string) (*model.Shipment, error)
}

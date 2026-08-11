package repository

import (
	"context"
	"shippingservice/domain/model"
	"shippingservice/domain/value"
)

//go:generate go run go.uber.org/mock/mockgen -source=shipment_repository.go -destination=mocks/mock_shipment_repository.go -package=repomocks

type ShipmentRepository interface {
	// Create generates a new tracking number and persists a new shipment for the given order.
	Create(ctx context.Context, orderID uint64, address value.Address) (*model.Shipment, error)
	GetByID(ctx context.Context, id uint64) (*model.Shipment, error)
	GetByTrackingNumber(ctx context.Context, trackingNumber string) (*model.Shipment, error)
	Update(ctx context.Context, shipment *model.Shipment) (*model.Shipment, error)
}

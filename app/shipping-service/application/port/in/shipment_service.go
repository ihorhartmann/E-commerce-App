package in

import (
	"context"
	"shippingservice/domain/model"
	"shippingservice/domain/value"
)

//go:generate go run go.uber.org/mock/mockgen -source=shipment_service.go -destination=mocks/mock_shipment_service.go -package=servicemocks

type ShipmentService interface {
	GetByTrackingNumber(ctx context.Context, trackingNumber string) (*model.Shipment, error)
	CreateShipment(ctx context.Context, correlationID string, orderID uint64, address value.Address) (*model.Shipment, error)
	StartDelivery(ctx context.Context, id uint64) (*model.Shipment, error)
	Deliver(ctx context.Context, id uint64) (*model.Shipment, error)
	Lose(ctx context.Context, id uint64) (*model.Shipment, error)
	Cancel(ctx context.Context, id uint64) (*model.Shipment, error)
	ConfirmReturnedToInventory(ctx context.Context, id uint64) (*model.Shipment, error)
}

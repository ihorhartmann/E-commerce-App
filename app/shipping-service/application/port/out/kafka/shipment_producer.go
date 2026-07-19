package kafka

import (
	"context"
	"shippingservice/domain/model"
)

type ShipmentProducer interface {
	PublishShipmentStatusChanged(ctx context.Context, correlationID string, shipment *model.Shipment) error
	PublishShipmentReturnedToInventory(ctx context.Context, correlationID string, shipment *model.Shipment) error
	PublishShipmentCreationFailed(ctx context.Context, correlationID string, orderID uint64) error
	Close() error
}

package kafka

import (
	"context"
	"shippingservice/domain/model"
)

type ShipmentProducer interface {
	PublishShipmentStatusChanged(ctx context.Context, shipment *model.Shipment) error
}

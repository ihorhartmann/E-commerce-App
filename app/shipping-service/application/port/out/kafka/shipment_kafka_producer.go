package kafka

import (
	"context"
	"shippingservice/domain/model"
)

//go:generate go run go.uber.org/mock/mockgen -source=shipment_kafka_producer.go -destination=mocks/mock_shipment_kafka_producer.go -package=kafkamocks

type ShipmentKafkaProducer interface {
	PublishShipmentStatusChanged(ctx context.Context, correlationID string, shipment *model.Shipment) error
	PublishShipmentReturnedToInventory(ctx context.Context, correlationID string, shipment *model.Shipment) error
	PublishShipmentCreationFailed(ctx context.Context, correlationID string, orderID uint64) error
	Close() error
}

package in

import (
	"context"
	idempotencydto "shippingservice/application/port/out/idempotency/dto"
	"shippingservice/domain/model"
	"shippingservice/domain/value"

	"github.com/google/uuid"
)

//go:generate go run go.uber.org/mock/mockgen -source=shipment_service.go -destination=mocks/mock_shipment_service.go -package=servicemocks

type ShipmentService interface {
	// GetByTrackingNumber finds any shipment - for couriers, who look shipments up to pick them up.
	GetByTrackingNumber(ctx context.Context, trackingNumber string) (*model.Shipment, error)
	// GetCustomerShipmentByTrackingNumber finds a shipment only if it belongs to the customer; another customer's
	// shipment is reported as not found.
	GetCustomerShipmentByTrackingNumber(ctx context.Context, trackingNumber string, customerID uuid.UUID) (*model.Shipment, error)
	CreateShipment(ctx context.Context, msg idempotencydto.KafkaMessageMetadata, orderID uint64, customerID uuid.UUID, address value.Address) (*model.Shipment, error)
	StartDelivery(ctx context.Context, id uint64, courierID uuid.UUID) (*model.Shipment, error)
	Deliver(ctx context.Context, id uint64, courierID uuid.UUID) (*model.Shipment, error)
	Lose(ctx context.Context, id uint64, courierID uuid.UUID) (*model.Shipment, error)
	Cancel(ctx context.Context, id uint64) (*model.Shipment, error)
	ConfirmReturnedToInventory(ctx context.Context, id uint64, courierID uuid.UUID) (*model.Shipment, error)
}

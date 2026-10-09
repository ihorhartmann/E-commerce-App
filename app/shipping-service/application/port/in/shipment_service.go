package in

import (
	"context"
	"shippingservice/application/port/in/dto"
	idempotencydto "shippingservice/application/port/out/idempotency/dto"
	"shippingservice/domain/model"
	"shippingservice/domain/value"

	"github.com/google/uuid"
)

//go:generate go run go.uber.org/mock/mockgen -source=shipment_service.go -destination=mocks/mock_shipment_service.go -package=servicemocks

type ShipmentService interface {
	// GetByID finds any shipment - couriers may open every one of them.
	GetByID(ctx context.Context, id uint64) (*model.Shipment, error)
	// GetAvailableShipments returns the page-th page (zero-based) of size shipments no courier has picked up yet,
	// oldest first, so none is left waiting.
	GetAvailableShipments(ctx context.Context, page, size int) (*dto.ShipmentPageDTO, error)
	// GetCourierActiveShipments returns the page-th page (zero-based) of size shipments the courier still has to act on -
	// deliver, or bring back to the inventory after a cancellation - oldest first.
	GetCourierActiveShipments(ctx context.Context, courierID uuid.UUID, page, size int) (*dto.ShipmentPageDTO, error)
	CreateShipment(ctx context.Context, msg idempotencydto.KafkaMessageMetadata, orderID uint64, address value.Address) (*model.Shipment, error)
	StartDelivery(ctx context.Context, id uint64, courierID uuid.UUID) (*model.Shipment, error)
	Deliver(ctx context.Context, id uint64, courierID uuid.UUID) (*model.Shipment, error)
	Lose(ctx context.Context, id uint64, courierID uuid.UUID) (*model.Shipment, error)
	Cancel(ctx context.Context, id uint64) (*model.Shipment, error)
	ConfirmReturnedToInventory(ctx context.Context, id uint64, courierID uuid.UUID) (*model.Shipment, error)
}

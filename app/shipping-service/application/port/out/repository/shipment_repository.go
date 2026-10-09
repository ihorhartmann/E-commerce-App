package repository

import (
	"context"
	"shippingservice/domain/model"

	"github.com/google/uuid"
)

//go:generate go run go.uber.org/mock/mockgen -source=shipment_repository.go -destination=mocks/mock_shipment_repository.go -package=repomocks

type ShipmentRepository interface {
	Create(ctx context.Context, shipment *model.Shipment) (*model.Shipment, error)
	GetByID(ctx context.Context, id uint64) (*model.Shipment, error)
	// GetAvailableShipments returns at most limit Created shipments, oldest first, skipping the first offset, and the
	// number of all of them.
	GetAvailableShipments(ctx context.Context, offset, limit int) ([]*model.Shipment, int64, error)
	// GetActiveShipmentsByCourier returns at most limit of the courier's shipments that are in delivery or cancelled but
	// still with them, oldest first, skipping the first offset, and the number of all of them.
	GetActiveShipmentsByCourier(ctx context.Context, courierID uuid.UUID, offset, limit int) ([]*model.Shipment, int64, error)
	Update(ctx context.Context, shipment *model.Shipment) (*model.Shipment, error)
}

package in

import (
	"context"
	"inventoryservice/application/dto"
	idempotencydto "inventoryservice/application/port/out/idempotency/dto"
	"inventoryservice/domain/model"
)

//go:generate go run go.uber.org/mock/mockgen -source=reservation_service.go -destination=mocks/mock_reservation_service.go -package=mocks

type ReservationService interface {
	ReserveItems(ctx context.Context, orderID uint64, reservationDTOs []dto.ReservationDTO) ([]*model.Product, error)
	Cancel(ctx context.Context, msg idempotencydto.KafkaMessageMetadata, orderID uint64) error
	HoldByCourier(ctx context.Context, msg idempotencydto.KafkaMessageMetadata, orderID uint64) error
	Fulfill(ctx context.Context, msg idempotencydto.KafkaMessageMetadata, orderID uint64) error
}

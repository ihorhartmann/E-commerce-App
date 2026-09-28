package in

import (
	"context"
	"inventoryservice/application/dto"
	idempotencydto "inventoryservice/application/port/out/idempotency/dto"
	"inventoryservice/domain/model"
)

//go:generate go run go.uber.org/mock/mockgen -source=product_service.go -destination=mocks/mock_product_service.go -package=mocks

type ProductService interface {
	Create(ctx context.Context, product *model.Product) (*model.ProductStock, error)
	Update(ctx context.Context, product *model.Product) (*model.ProductStock, error)
	Get(ctx context.Context, id uint64) (*model.ProductStock, error)
	Delete(ctx context.Context, id uint64) error
	Restock(ctx context.Context, msg idempotencydto.KafkaMessageMetadata, items []dto.ReservationDTO) error
}

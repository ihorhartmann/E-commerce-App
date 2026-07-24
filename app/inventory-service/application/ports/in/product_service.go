package in

import (
	"context"
	"inventoryservice/application/dto"
	"inventoryservice/domain/model"
)

type ProductService interface {
	Create(ctx context.Context, product *model.Product) (*model.Product, error)
	Update(ctx context.Context, product *model.Product) (*model.Product, error)
	Get(ctx context.Context, id uint64) (*model.Product, error)
	Delete(ctx context.Context, id uint64) error
	Restock(ctx context.Context, items []dto.ReservationDto) error
}

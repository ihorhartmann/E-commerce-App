package repository

import (
	"context"

	"inventoryservice/domain/model"
)

type ProductRepository interface {
	Create(ctx context.Context, product *model.Product) (*model.Product, error)
	Update(ctx context.Context, product *model.Product) (*model.Product, error)
	GetByID(ctx context.Context, id uint64) (*model.Product, error)
	FindByIDs(ctx context.Context, ids []uint64) ([]*model.Product, error)
	Delete(ctx context.Context, id uint64) error
}

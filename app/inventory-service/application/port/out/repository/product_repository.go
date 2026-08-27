package repository

import (
	"context"

	"inventoryservice/domain/model"
)

//go:generate go run go.uber.org/mock/mockgen -source=product_repository.go -destination=mocks/mock_product_repository.go -package=mocks

type ProductRepository interface {
	Create(ctx context.Context, product *model.Product) (*model.Product, error)
	Update(ctx context.Context, product *model.Product) (*model.Product, error)
	GetByID(ctx context.Context, id uint64) (*model.Product, error)
	GetByIDs(ctx context.Context, ids []uint64) ([]*model.Product, error)
	GetByIDWithReservations(ctx context.Context, id uint64) (*model.Product, error)
	Delete(ctx context.Context, id uint64) error
}

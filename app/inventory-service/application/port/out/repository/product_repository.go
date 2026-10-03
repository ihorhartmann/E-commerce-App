package repository

import (
	"context"

	"inventoryservice/domain/model"
)

//go:generate go run go.uber.org/mock/mockgen -source=product_repository.go -destination=mocks/mock_product_repository.go -package=mocks

type ProductRepository interface {
	Create(ctx context.Context, product *model.Product) (*model.Product, error)
	// Update stores product only if it hasn't changed since it was loaded (its version still
	// matches), and returns it as the database now holds it, with the incremented version. It returns
	// ErrConcurrentUpdate if the product was changed in between, e.g. by a concurrent reservation.
	Update(ctx context.Context, product *model.Product) (*model.Product, error)
	GetByID(ctx context.Context, id uint64) (*model.Product, error)
	// GetByIDs returns one product per distinct id, in the order the ids first appear, or
	// ErrProductNotFound if any of them doesn't exist.
	GetByIDs(ctx context.Context, ids []uint64) ([]*model.Product, error)
	// Delete deletes product only if it hasn't changed since it was loaded (its version still
	// matches), and returns ErrConcurrentUpdate otherwise - e.g. if its stock got reserved in between.
	Delete(ctx context.Context, product *model.Product) error
}

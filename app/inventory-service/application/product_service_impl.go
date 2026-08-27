package application

import (
	"context"
	"inventoryservice/application/dto"
	"inventoryservice/application/port/in"
	"inventoryservice/application/port/out/repository"
	"inventoryservice/application/port/out/repository/transaction"
	"inventoryservice/domain"

	"inventoryservice/domain/model"
)

type ProductServiceImpl struct {
	in.ProductService
	productRepository repository.ProductRepository
	txExecutor        transaction.Executor
}

func NewProductService(products repository.ProductRepository, txExecutor transaction.Executor) in.ProductService {
	return &ProductServiceImpl{productRepository: products, txExecutor: txExecutor}
}

func (ps *ProductServiceImpl) Create(ctx context.Context, product *model.Product) (*model.Product, error) {
	return ps.productRepository.Create(ctx, product)
}

func (ps *ProductServiceImpl) Update(ctx context.Context, product *model.Product) (*model.Product, error) {
	updated, err := ps.productRepository.Update(ctx, product)
	if err != nil {
		return nil, err
	}

	// Re-read via the joined query so Reserved()/Available() on the returned product reflect
	// current reservations instead of always reading as unreserved.
	return ps.productRepository.GetByIDWithReservations(ctx, updated.ID())
}

func (ps *ProductServiceImpl) Get(ctx context.Context, id uint64) (*model.Product, error) {
	if id == 0 {
		return nil, domain.ErrInvalidInput
	}

	return ps.productRepository.GetByIDWithReservations(ctx, id)
}

func (ps *ProductServiceImpl) Delete(ctx context.Context, id uint64) error {
	if id == 0 {
		return domain.ErrInvalidInput
	}

	return ps.productRepository.Delete(ctx, id)
}

func (ps *ProductServiceImpl) Restock(ctx context.Context, items []dto.ReservationDTO) error {
	return ps.txExecutor.Transaction(ctx, func(ctx context.Context) error {
		for _, item := range items {
			product, err := ps.productRepository.GetByID(ctx, item.ProductID)
			if err != nil {
				return err
			}

			if _, err = ps.productRepository.Update(ctx, product.Restock(item.Quantity)); err != nil {
				return err
			}
		}

		return nil
	})
}

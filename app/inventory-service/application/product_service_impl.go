package application

import (
	"context"
	"inventoryservice/application/dto"
	"inventoryservice/application/ports/in"
	"inventoryservice/application/ports/out/repository"
	"inventoryservice/domain"

	"inventoryservice/domain/model"
)

type ProductServiceImpl struct {
	in.ProductService
	productRepository repository.ProductRepository
}

func NewProductService(products repository.ProductRepository) *ProductServiceImpl {
	return &ProductServiceImpl{productRepository: products}
}

func (ps *ProductServiceImpl) Create(ctx context.Context, product *model.Product) (*model.Product, error) {
	return ps.productRepository.Create(ctx, product)
}

func (ps *ProductServiceImpl) Update(ctx context.Context, product *model.Product) (*model.Product, error) {
	return ps.productRepository.Update(ctx, product)
}

func (ps *ProductServiceImpl) Get(ctx context.Context, id uint64) (*model.Product, error) {
	if id == 0 {
		return nil, domain.ErrInvalidInput
	}

	return ps.productRepository.GetByID(ctx, id)
}

func (ps *ProductServiceImpl) Delete(ctx context.Context, id uint64) error {
	if id == 0 {
		return domain.ErrInvalidInput
	}

	return ps.productRepository.Delete(ctx, id)
}

// TODO make it tx and try to bulk update products in the future
func (ps *ProductServiceImpl) Restock(ctx context.Context, items []dto.ReservationDto) error {
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
}

package usecases

import (
	"context"
	"inventoryservice/entities"
	"inventoryservice/usecases/ports/repository"

	"inventoryservice/entities/model"
)

type ProductUseCase struct {
	productRepository repository.ProductRepository
}

func NewProductUseCase(products repository.ProductRepository) *ProductUseCase {
	return &ProductUseCase{productRepository: products}
}

func (uc *ProductUseCase) Create(ctx context.Context, product *model.Product) (*model.Product, error) {
	return uc.productRepository.Create(ctx, product)
}

func (uc *ProductUseCase) Update(ctx context.Context, product *model.Product) (*model.Product, error) {
	return uc.productRepository.Update(ctx, product)
}

func (uc *ProductUseCase) Get(ctx context.Context, id uint64) (*model.Product, error) {
	if id == 0 {
		return nil, entities.ErrInvalidInput
	}

	return uc.productRepository.GetByID(ctx, id)
}

func (uc *ProductUseCase) Delete(ctx context.Context, id uint64) error {
	if id == 0 {
		return entities.ErrInvalidInput
	}

	return uc.productRepository.Delete(ctx, id)
}

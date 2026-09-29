package application

import (
	"context"
	"inventoryservice/application/dto"
	"inventoryservice/application/port/in"
	"inventoryservice/application/port/out/idempotency"
	idempotencydto "inventoryservice/application/port/out/idempotency/dto"
	"inventoryservice/application/port/out/repository"
	"inventoryservice/application/port/out/repository/transaction"
	"inventoryservice/domain"

	"inventoryservice/domain/model"
)

type ProductServiceImpl struct {
	in.ProductService
	productRepository     repository.ProductRepository
	processedMessageStore idempotency.ProcessedKafkaMessageStore
	txExecutor            transaction.Executor
}

func NewProductService(
	products repository.ProductRepository,
	processedMessageStore idempotency.ProcessedKafkaMessageStore,
	txExecutor transaction.Executor,
) in.ProductService {
	return &ProductServiceImpl{productRepository: products, processedMessageStore: processedMessageStore, txExecutor: txExecutor}
}

func (ps *ProductServiceImpl) Create(ctx context.Context, product *model.Product) (*model.Product, error) {
	return ps.productRepository.Create(ctx, product)
}

// Update applies the new details to the stored product, keeping what it has reserved.
func (ps *ProductServiceImpl) Update(ctx context.Context, update dto.UpdateProductDTO) (*model.Product, error) {
	product, err := ps.productRepository.GetByID(ctx, update.ID)
	if err != nil {
		return nil, err
	}

	updated, err := product.Update(update.Name, update.Description, update.Price, update.Quantity)
	if err != nil {
		return nil, err
	}

	return ps.productRepository.Update(ctx, updated)
}

func (ps *ProductServiceImpl) Get(ctx context.Context, id uint64) (*model.Product, error) {
	if id == 0 {
		return nil, domain.ErrInvalidInput
	}

	return ps.productRepository.GetByID(ctx, id)
}

// Delete deletes the product unless some of its stock is reserved.
func (ps *ProductServiceImpl) Delete(ctx context.Context, id uint64) error {
	if id == 0 {
		return domain.ErrInvalidInput
	}

	product, err := ps.productRepository.GetByID(ctx, id)
	if err != nil {
		return err
	}

	if err = product.ValidateDeletion(); err != nil {
		return err
	}

	return ps.productRepository.Delete(ctx, product)
}

func (ps *ProductServiceImpl) Restock(ctx context.Context, msg idempotencydto.KafkaMessageMetadata, items []dto.ReservationDTO) error {
	return ps.txExecutor.Transaction(ctx, func(ctx context.Context) error {
		alreadyProcessed, err := ps.processedMessageStore.MarkProcessed(ctx, msg)
		if alreadyProcessed || err != nil {
			return err
		}

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

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

// Create returns the created product's stock - a product that was just created has no reservations yet.
func (ps *ProductServiceImpl) Create(ctx context.Context, product *model.Product) (*model.ProductStock, error) {
	created, err := ps.productRepository.Create(ctx, product)
	if err != nil {
		return nil, err
	}

	return model.NewProductStock(created, nil)
}

func (ps *ProductServiceImpl) Update(ctx context.Context, product *model.Product) (*model.ProductStock, error) {
	updated, err := ps.productRepository.Update(ctx, product)
	if err != nil {
		return nil, err
	}

	// Re-read with the product's reservations, which the update itself doesn't carry.
	return ps.productRepository.GetStockByID(ctx, updated.ID())
}

func (ps *ProductServiceImpl) Get(ctx context.Context, id uint64) (*model.ProductStock, error) {
	if id == 0 {
		return nil, domain.ErrInvalidInput
	}

	return ps.productRepository.GetStockByID(ctx, id)
}

func (ps *ProductServiceImpl) Delete(ctx context.Context, id uint64) error {
	if id == 0 {
		return domain.ErrInvalidInput
	}

	return ps.productRepository.Delete(ctx, id)
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

package application

import (
	"context"
	"fmt"
	"inventoryservice/application/dto"
	"inventoryservice/application/port/in"
	"inventoryservice/application/port/out/idempotency"
	idempotencydto "inventoryservice/application/port/out/idempotency/dto"
	"inventoryservice/application/port/out/repository"
	"inventoryservice/application/port/out/repository/transaction"
	"log/slog"
	"math"

	"inventoryservice/domain/model"
)

// maxPageSize is the most products GetProducts returns at once.
const maxPageSize = 100

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

	if err = product.Update(update.Name, update.Description, update.Price, update.Quantity); err != nil {
		return nil, err
	}

	return ps.productRepository.Update(ctx, product)
}

func (ps *ProductServiceImpl) Get(ctx context.Context, id uint64) (*model.Product, error) {
	if id == 0 {
		return nil, ErrInvalidProductID
	}

	return ps.productRepository.GetByID(ctx, id)
}

func (ps *ProductServiceImpl) GetProducts(ctx context.Context, page, size int) (*dto.ProductPageDTO, error) {
	// the page limit keeps page*size from overflowing
	if page < 0 || size < 1 || size > maxPageSize || page > math.MaxInt32 {
		return nil, ErrInvalidPage.
			WithAttrs(slog.Int("page", page), slog.Int("size", size)).
			WithExternalMessage(fmt.Sprintf("page must be at least 0 and size between 1 and %d", maxPageSize))
	}

	products, totalElements, err := ps.productRepository.GetProducts(ctx, page*size, size)
	if err != nil {
		return nil, err
	}

	return &dto.ProductPageDTO{Products: products, TotalElements: totalElements}, nil
}

// Delete deletes the product unless some of its stock is reserved.
func (ps *ProductServiceImpl) Delete(ctx context.Context, id uint64) error {
	if id == 0 {
		return ErrInvalidProductID
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

			product.Restock(item.Quantity)
			if _, err = ps.productRepository.Update(ctx, product); err != nil {
				return err
			}
		}

		return nil
	})
}

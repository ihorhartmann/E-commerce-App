package repository

import (
	"context"
	"errors"
	"fmt"

	"inventoryservice/application/port/out/repository"
	"inventoryservice/domain/model"
	"inventoryservice/infrastructure/gorm/transaction"
	"inventoryservice/infrastructure/repository/entity"

	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

type postgresProductRepository struct {
	repository.ProductRepository
	db *gorm.DB
}

func NewPostgresProductRepository(db *gorm.DB) repository.ProductRepository {
	return &postgresProductRepository{db: db}
}

func (r *postgresProductRepository) Create(ctx context.Context, product *model.Product) (*model.Product, error) {
	db := transaction.FromWithDefault(ctx, r.db)

	row := entity.ToProductEntity(product)
	if err := db.WithContext(ctx).Clauses(clause.Returning{}).Create(row).Error; err != nil {
		return nil, err
	}

	return entity.FromProductEntity(row)
}

func (r *postgresProductRepository) Update(ctx context.Context, product *model.Product) (*model.Product, error) {
	db := transaction.FromWithDefault(ctx, r.db)

	row := entity.ToProductEntity(product)
	version := row.Version
	// RETURNING reads the stored row back into row, in the same statement
	result := db.WithContext(ctx).
		Model(row).
		Clauses(clause.Returning{}).
		Where("id = ? AND version = ?", row.ID, version).
		Updates(map[string]any{
			"name":           row.Name,
			"description":    row.Description,
			"price_amount":   row.PriceAmount,
			"price_currency": row.PriceCurrency,
			"quantity":       row.Quantity,
			"reserved":       row.Reserved,
			"version":        gorm.Expr("version + 1"),
		})
	if result.Error != nil {
		return nil, result.Error
	}
	if result.RowsAffected == 0 {
		// Nothing matched: either the product is gone, or someone changed it after it was loaded.
		if _, err := r.GetByID(ctx, row.ID); err != nil {
			return nil, err
		}
		return nil, fmt.Errorf("%w: product %d changed since version %d", repository.ErrConcurrentUpdate, row.ID, version)
	}

	return entity.FromProductEntity(row)
}

func (r *postgresProductRepository) GetByID(ctx context.Context, id uint64) (*model.Product, error) {
	db := transaction.FromWithDefault(ctx, r.db)

	var row entity.Product
	if err := db.WithContext(ctx).First(&row, "id = ?", id).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, repository.ErrProductNotFound
		}
		return nil, err
	}

	return entity.FromProductEntity(&row)
}

func (r *postgresProductRepository) GetByIDs(ctx context.Context, ids []uint64) ([]*model.Product, error) {
	db := transaction.FromWithDefault(ctx, r.db)
	distinctIDs := distinct(ids)

	var rows []*entity.Product
	if err := db.WithContext(ctx).Find(&rows, "id IN ?", distinctIDs).Error; err != nil {
		return nil, err
	}

	found := make(map[uint64]*entity.Product, len(rows))
	for _, row := range rows {
		found[row.ID] = row
	}

	products := make([]*model.Product, 0, len(distinctIDs))
	for _, id := range distinctIDs {
		row, ok := found[id]
		if !ok {
			return nil, fmt.Errorf("%w: product %d", repository.ErrProductNotFound, id)
		}

		product, err := entity.FromProductEntity(row)
		if err != nil {
			return nil, err
		}
		products = append(products, product)
	}

	return products, nil
}

// distinct returns ids without repetitions, keeping the order in which each id first appears.
func distinct(ids []uint64) []uint64 {
	seen := make(map[uint64]struct{}, len(ids))
	result := make([]uint64, 0, len(ids))
	for _, id := range ids {
		if _, ok := seen[id]; ok {
			continue
		}
		seen[id] = struct{}{}
		result = append(result, id)
	}

	return result
}

func (r *postgresProductRepository) Delete(ctx context.Context, product *model.Product) error {
	db := transaction.FromWithDefault(ctx, r.db)

	result := db.WithContext(ctx).Delete(&entity.Product{}, "id = ? AND version = ?", product.ID(), product.Version())
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		// Nothing matched: either the product is gone, or someone changed it after it was loaded.
		if _, err := r.GetByID(ctx, product.ID()); err != nil {
			return err
		}
		return fmt.Errorf("%w: product %d changed since version %d", repository.ErrConcurrentUpdate, product.ID(), product.Version())
	}

	return nil
}

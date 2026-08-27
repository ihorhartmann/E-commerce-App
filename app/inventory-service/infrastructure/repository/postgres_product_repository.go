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
	if err := db.WithContext(ctx).Create(row).Error; err != nil {
		return nil, err
	}

	return entity.FromProductEntity(row)
}

func (r *postgresProductRepository) Update(ctx context.Context, product *model.Product) (*model.Product, error) {
	db := transaction.FromWithDefault(ctx, r.db)

	row := entity.ToProductEntity(product)
	result := db.WithContext(ctx).Model(&entity.Product{}).Where("id = ?", row.ID).Select("*").Updates(row)
	if result.Error != nil {
		return nil, result.Error
	}
	if result.RowsAffected == 0 {
		return nil, repository.ErrProductNotFound
	}

	return product, nil
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

	var rows []*entity.Product
	if err := db.WithContext(ctx).Find(&rows, "id IN ?", ids).Error; err != nil {
		return nil, err
	}

	found := make(map[uint64]*entity.Product, len(rows))
	for _, row := range rows {
		found[row.ID] = row
	}

	products := make([]*model.Product, 0, len(ids))
	for _, id := range ids {
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

func (r *postgresProductRepository) GetByIDWithReservations(ctx context.Context, id uint64) (*model.Product, error) {
	db := transaction.FromWithDefault(ctx, r.db)

	type joinedRow struct {
		entity.Product
		ReservationID       *uint64 `gorm:"column:reservation_id"`
		ReservationOrderID  *uint64 `gorm:"column:reservation_order_id"`
		ReservationQuantity *uint   `gorm:"column:reservation_quantity"`
		ReservationHolder   *string `gorm:"column:reservation_holder"`
	}

	var rows []joinedRow
	err := db.WithContext(ctx).
		Table("products").
		Select(
			"products.*, "+
				"stock_reservations.id AS reservation_id, "+
				"stock_reservations.order_id AS reservation_order_id, "+
				"stock_reservations.quantity AS reservation_quantity, "+
				"stock_reservations.holder AS reservation_holder",
		).
		Joins("LEFT JOIN stock_reservations ON stock_reservations.product_id = products.id").
		Where("products.id = ?", id).
		Find(&rows).Error
	if err != nil {
		return nil, err
	}
	if len(rows) == 0 {
		return nil, repository.ErrProductNotFound
	}

	product, err := entity.FromProductEntity(&rows[0].Product)
	if err != nil {
		return nil, err
	}

	reservations := make([]*model.StockReservation, 0, len(rows))
	for _, row := range rows {
		if row.ReservationID == nil {
			continue
		}

		reservation, err := model.NewStockReservation(
			*row.ReservationID,
			row.Product.ID,
			*row.ReservationOrderID,
			*row.ReservationQuantity,
			model.ReservationHolder(*row.ReservationHolder),
		)
		if err != nil {
			return nil, err
		}
		reservations = append(reservations, reservation)
	}

	return product.WithStockReservations(reservations), nil
}

func (r *postgresProductRepository) Delete(ctx context.Context, id uint64) error {
	db := transaction.FromWithDefault(ctx, r.db)

	result := db.WithContext(ctx).Delete(&entity.Product{}, "id = ?", id)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return repository.ErrProductNotFound
	}

	return nil
}

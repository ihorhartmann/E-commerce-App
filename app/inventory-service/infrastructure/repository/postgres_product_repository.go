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

func (r *postgresProductRepository) GetStockByID(ctx context.Context, id uint64) (*model.ProductStock, error) {
	stocks, err := r.getStocks(ctx, []uint64{id})
	if err != nil {
		return nil, err
	}

	return stocks[0], nil
}

func (r *postgresProductRepository) GetStocksByIDs(ctx context.Context, ids []uint64) ([]*model.ProductStock, error) {
	return r.getStocks(ctx, ids)
}

// productWithReservationRow is one row of the products LEFT JOIN stock_reservations query: the
// product's columns plus one of its reservations, or nil reservation columns if it has none.
type productWithReservationRow struct {
	entity.Product
	ReservationID       *uint64 `gorm:"column:reservation_id"`
	ReservationOrderID  *uint64 `gorm:"column:reservation_order_id"`
	ReservationQuantity *uint   `gorm:"column:reservation_quantity"`
	ReservationHolder   *string `gorm:"column:reservation_holder"`
}

// getStocks loads the products with the given ids together with their stock reservations
// in a single query, so both come from the same snapshot. It returns one stock per distinct id, in
// the order the ids first appear, or ErrProductNotFound if any of them doesn't exist.
func (r *postgresProductRepository) getStocks(ctx context.Context, ids []uint64) ([]*model.ProductStock, error) {
	db := transaction.FromWithDefault(ctx, r.db)
	distinctIDs := distinct(ids)

	var rows []productWithReservationRow
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
		Where("products.id IN ?", distinctIDs).
		Find(&rows).Error
	if err != nil {
		return nil, err
	}

	productRows := make(map[uint64]*entity.Product, len(distinctIDs))
	reservationsByProductID := make(map[uint64][]*model.StockReservation, len(distinctIDs))
	for _, row := range rows {
		productRows[row.Product.ID] = &row.Product
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
		reservationsByProductID[row.Product.ID] = append(reservationsByProductID[row.Product.ID], reservation)
	}

	stocks := make([]*model.ProductStock, 0, len(distinctIDs))
	for _, id := range distinctIDs {
		productRow, ok := productRows[id]
		if !ok {
			return nil, fmt.Errorf("%w: product %d", repository.ErrProductNotFound, id)
		}

		product, err := entity.FromProductEntity(productRow)
		if err != nil {
			return nil, err
		}

		stock, err := model.NewProductStock(product, reservationsByProductID[id])
		if err != nil {
			return nil, err
		}
		stocks = append(stocks, stock)
	}

	return stocks, nil
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

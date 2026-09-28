package repository

import (
	"context"
	"errors"

	"inventoryservice/application/port/out/repository"
	"inventoryservice/domain/model"
	"inventoryservice/infrastructure/gorm/transaction"
	"inventoryservice/infrastructure/repository/entity"

	"gorm.io/gorm"
)

type postgresReservationRepository struct {
	repository.ReservationRepository
	db *gorm.DB
}

func NewPostgresReservationRepository(db *gorm.DB) repository.ReservationRepository {
	return &postgresReservationRepository{db: db}
}

func (r *postgresReservationRepository) CreateBatch(ctx context.Context, reservations []*model.StockReservation) ([]*model.StockReservation, error) {
	db := transaction.FromWithDefault(ctx, r.db)

	rows := make([]*entity.StockReservation, 0, len(reservations))
	for _, reservation := range reservations {
		row := entity.ToStockReservationEntity(reservation)
		rows = append(rows, row)
	}

	if err := db.WithContext(ctx).Create(&rows).Error; err != nil {
		return nil, err
	}

	created := make([]*model.StockReservation, 0, len(rows))
	for _, row := range rows {
		reservation, err := entity.FromStockReservationEntity(row)
		if err != nil {
			return nil, err
		}
		created = append(created, reservation)
	}

	return created, nil
}

func (r *postgresReservationRepository) Update(ctx context.Context, reservation *model.StockReservation) (*model.StockReservation, error) {
	db := transaction.FromWithDefault(ctx, r.db)

	row := entity.ToStockReservationEntity(reservation)
	result := db.WithContext(ctx).Model(&entity.StockReservation{}).Where("id = ?", row.ID).Select("*").Updates(row)
	if result.Error != nil {
		return nil, result.Error
	}
	if result.RowsAffected == 0 {
		return nil, repository.ErrReservationNotFound
	}

	return reservation, nil
}

func (r *postgresReservationRepository) GetByID(ctx context.Context, reservationID uint64) (*model.StockReservation, error) {
	db := transaction.FromWithDefault(ctx, r.db)

	var row entity.StockReservation
	if err := db.WithContext(ctx).First(&row, "id = ?", reservationID).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, repository.ErrReservationNotFound
		}
		return nil, err
	}

	return entity.FromStockReservationEntity(&row)
}

func (r *postgresReservationRepository) GetByOrderID(ctx context.Context, orderID uint64) ([]*model.StockReservation, error) {
	db := transaction.FromWithDefault(ctx, r.db)

	var rows []*entity.StockReservation
	if err := db.WithContext(ctx).Find(&rows, "order_id = ?", orderID).Error; err != nil {
		return nil, err
	}
	if len(rows) == 0 {
		return nil, repository.ErrReservationNotFound
	}

	return toReservations(rows)
}

func (r *postgresReservationRepository) DeleteByIDs(ctx context.Context, reservationIDs []uint64) error {
	db := transaction.FromWithDefault(ctx, r.db)

	result := db.WithContext(ctx).Delete(&entity.StockReservation{}, "id IN ?", reservationIDs)
	if result.Error != nil {
		return result.Error
	}
	if int(result.RowsAffected) != len(reservationIDs) {
		return repository.ErrReservationNotFound
	}

	return nil
}

func toReservations(rows []*entity.StockReservation) ([]*model.StockReservation, error) {
	reservations := make([]*model.StockReservation, 0, len(rows))
	for _, row := range rows {
		reservation, err := entity.FromStockReservationEntity(row)
		if err != nil {
			return nil, err
		}
		reservations = append(reservations, reservation)
	}

	return reservations, nil
}

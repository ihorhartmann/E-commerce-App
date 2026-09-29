package repository

import (
	"context"
	"errors"
	"fmt"

	"shippingservice/adapter/out/repository/entity"
	"shippingservice/adapter/out/repository/transaction"
	"shippingservice/application/port/out/repository"
	"shippingservice/domain/model"

	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

type postgresShipmentRepository struct {
	repository.ShipmentRepository
	db *gorm.DB
}

func NewPostgresShipmentRepository(db *gorm.DB) repository.ShipmentRepository {
	return &postgresShipmentRepository{db: db}
}

// Create returns ErrShipmentAlreadyExists if the order already has a shipment - a permanent error,
// so the consumer doesn't retry it. ON CONFLICT DO NOTHING (instead of catching Postgres' unique
// violation) keeps the transaction usable and can't be confused with a tracking_number collision.
func (r *postgresShipmentRepository) Create(ctx context.Context, shipment *model.Shipment) (*model.Shipment, error) {
	db := transaction.FromWithDefault(ctx, r.db)

	row := entity.ToShipmentEntity(shipment)
	result := db.WithContext(ctx).
		Clauses(clause.OnConflict{Columns: []clause.Column{{Name: "order_id"}}, DoNothing: true}).
		Create(row)
	if result.Error != nil {
		return nil, result.Error
	}
	if result.RowsAffected == 0 {
		return nil, fmt.Errorf("%w: order %d", repository.ErrShipmentAlreadyExists, shipment.OrderId())
	}

	return entity.FromShipmentEntity(row)
}

func (r *postgresShipmentRepository) GetByID(ctx context.Context, id uint64) (*model.Shipment, error) {
	db := transaction.FromWithDefault(ctx, r.db)

	var row entity.Shipment
	if err := db.WithContext(ctx).First(&row, "id = ?", id).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, repository.ErrShipmentNotFound
		}
		return nil, err
	}

	return entity.FromShipmentEntity(&row)
}

func (r *postgresShipmentRepository) GetByTrackingNumber(ctx context.Context, trackingNumber string) (*model.Shipment, error) {
	db := transaction.FromWithDefault(ctx, r.db)

	var row entity.Shipment
	if err := db.WithContext(ctx).First(&row, "tracking_number = ?", trackingNumber).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, repository.ErrShipmentNotFound
		}
		return nil, err
	}

	return entity.FromShipmentEntity(&row)
}

func (r *postgresShipmentRepository) Update(ctx context.Context, shipment *model.Shipment) (*model.Shipment, error) {
	db := transaction.FromWithDefault(ctx, r.db)

	row := entity.ToShipmentEntity(shipment)
	result := db.WithContext(ctx).Model(&entity.Shipment{}).Where("id = ?", row.ID).Select("*").Updates(row)
	if result.Error != nil {
		return nil, result.Error
	}
	if result.RowsAffected == 0 {
		return nil, repository.ErrShipmentNotFound
	}

	return shipment, nil
}

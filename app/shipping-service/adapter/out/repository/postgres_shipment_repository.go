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
		Clauses(clause.OnConflict{Columns: []clause.Column{{Name: "order_id"}}, DoNothing: true}, clause.Returning{}).
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
	version := row.Version
	// RETURNING reads the stored row back into row, in the same statement
	result := db.WithContext(ctx).
		Model(row).
		Clauses(clause.Returning{}).
		Where("id = ? AND version = ?", row.ID, version).
		Updates(map[string]any{
			"status":     row.Status,
			"holder":     row.Holder,
			"courier_id": row.CourierID,
			"updated_at": row.UpdatedAt,
			"version":    gorm.Expr("version + 1"),
		})
	if result.Error != nil {
		return nil, result.Error
	}
	if result.RowsAffected == 0 {
		// Nothing matched: either the shipment is gone, or someone changed it after it was loaded.
		if _, err := r.GetByID(ctx, row.ID); err != nil {
			return nil, err
		}
		return nil, fmt.Errorf("%w: shipment %d changed since version %d", repository.ErrConcurrentUpdate, row.ID, version)
	}

	return entity.FromShipmentEntity(row)
}

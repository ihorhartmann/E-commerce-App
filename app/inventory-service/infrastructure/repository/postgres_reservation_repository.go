package repository

import (
	"context"
	"errors"
	"fmt"
	"log/slog"

	"inventoryservice/application/port/out/repository"
	"inventoryservice/domain/model"
	"inventoryservice/infrastructure/gorm/transaction"
	"inventoryservice/infrastructure/repository/entity"

	"gorm.io/gorm"
	"gorm.io/gorm/clause"
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

	if err := db.WithContext(ctx).Clauses(clause.Returning{}).Create(&rows).Error; err != nil {
		return nil, ErrDatabaseFailure.
			WithInternalMessage("create reservations").
			WithAttrs(slog.Int("count", len(rows))).
			WithCause(err)
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
	version := row.Version
	result := db.WithContext(ctx).
		Model(row).
		Clauses(clause.Returning{}).
		Where("id = ? AND version = ?", row.ID, version).
		Updates(map[string]any{
			"holder":  row.Holder,
			"version": gorm.Expr("version + 1"),
		})
	if result.Error != nil {
		return nil, ErrDatabaseFailure.
			WithInternalMessage("update reservation").
			WithAttrs(slog.Uint64("reservationID", row.ID)).
			WithCause(result.Error)
	}
	if result.RowsAffected == 0 {
		// Nothing matched: either the reservation is gone, or someone changed it after it was loaded.
		if _, err := r.GetByID(ctx, row.ID); err != nil {
			return nil, err
		}
		return nil, repository.ErrConcurrentUpdate.
			WithInternalMessage("reservation changed since it was loaded").
			WithAttrs(slog.Uint64("reservationID", row.ID), slog.Uint64("version", version))
	}

	return entity.FromStockReservationEntity(row)
}

func (r *postgresReservationRepository) GetByID(ctx context.Context, reservationID uint64) (*model.StockReservation, error) {
	db := transaction.FromWithDefault(ctx, r.db)

	var row entity.StockReservation
	if err := db.WithContext(ctx).First(&row, "id = ?", reservationID).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, repository.ErrReservationNotFound.WithAttrs(slog.Uint64("reservationID", reservationID))
		}
		return nil, ErrDatabaseFailure.
			WithInternalMessage("get reservation").
			WithAttrs(slog.Uint64("reservationID", reservationID)).
			WithCause(err)
	}

	return entity.FromStockReservationEntity(&row)
}

func (r *postgresReservationRepository) GetByOrderID(ctx context.Context, orderID uint64) ([]*model.StockReservation, error) {
	db := transaction.FromWithDefault(ctx, r.db)

	var rows []*entity.StockReservation
	if err := db.WithContext(ctx).Find(&rows, "order_id = ?", orderID).Error; err != nil {
		return nil, ErrDatabaseFailure.
			WithInternalMessage("get reservations of order").
			WithAttrs(slog.Uint64("orderID", orderID)).
			WithCause(err)
	}
	if len(rows) == 0 {
		return nil, repository.ErrReservationNotFound.
			WithInternalMessage("order has no reservations").
			WithAttrs(slog.Uint64("orderID", orderID))
	}

	return toReservations(rows)
}

func (r *postgresReservationRepository) DeleteAll(ctx context.Context, reservations []*model.StockReservation) error {
	db := transaction.FromWithDefault(ctx, r.db)

	ids := make([]uint64, 0, len(reservations))
	idVersions := make([][]any, 0, len(reservations))
	for _, reservation := range reservations {
		ids = append(ids, reservation.ID())
		idVersions = append(idVersions, []any{reservation.ID(), reservation.Version()})
	}

	result := db.WithContext(ctx).Delete(&entity.StockReservation{}, "(id, version) IN ?", idVersions)
	if result.Error != nil {
		return ErrDatabaseFailure.
			WithInternalMessage("delete reservations").
			WithAttrs(slog.String("reservationIDs", fmt.Sprint(ids))).
			WithCause(result.Error)
	}
	if int(result.RowsAffected) == len(reservations) {
		return nil
	}

	// Not all matched: either some reservation is gone, or someone changed it after it was loaded. The
	// ones that did match are already deleted in this transaction (rolled back with the error), so
	// they count as existing too.
	var remaining int64
	if err := db.WithContext(ctx).Model(&entity.StockReservation{}).Where("id IN ?", ids).Count(&remaining).Error; err != nil {
		return ErrDatabaseFailure.
			WithInternalMessage("count remaining reservations").
			WithAttrs(slog.String("reservationIDs", fmt.Sprint(ids))).
			WithCause(err)
	}
	if int(remaining+result.RowsAffected) != len(reservations) {
		return repository.ErrReservationNotFound.
			WithInternalMessage("some of the reservations are gone").
			WithAttrs(slog.String("reservationIDs", fmt.Sprint(ids)))
	}
	return repository.ErrConcurrentUpdate.
		WithInternalMessage("some of the reservations changed since they were loaded").
		WithAttrs(slog.String("reservationIDs", fmt.Sprint(ids)))
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

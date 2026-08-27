package transaction

import (
	"context"
	"inventoryservice/application/port/out/repository/transaction"

	"gorm.io/gorm"
)

type gormTxExecutor struct {
	transaction.Executor
	db *gorm.DB
}

func NewGormTxManager(db *gorm.DB) transaction.Executor {
	return &gormTxExecutor{db: db}
}

func (m *gormTxExecutor) Transaction(ctx context.Context, action func(ctx context.Context) error) error {
	tx := m.db.Begin()
	if tx.Error != nil {
		return tx.Error
	}

	ctx = withTx(ctx, tx)

	if err := action(ctx); err != nil {
		tx.Rollback()
		return err
	}

	return tx.Commit().Error
}

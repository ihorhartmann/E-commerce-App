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

// Transaction runs action in a transaction. An error of action is ours already and is returned as it
// is - giving it to WithCause would replace its code; only the transaction's own failures get
// ErrTransactionFailure.
func (m *gormTxExecutor) Transaction(ctx context.Context, action func(ctx context.Context) error) error {
	tx := m.db.Begin()
	if tx.Error != nil {
		return ErrTransactionFailure.WithInternalMessage("begin transaction").WithCause(tx.Error)
	}

	ctx = withTx(ctx, tx)

	if err := action(ctx); err != nil {
		tx.Rollback()
		return err
	}

	if err := tx.Commit().Error; err != nil {
		return ErrTransactionFailure.WithInternalMessage("commit transaction").WithCause(err)
	}

	return nil
}

package transaction

import (
	"context"

	"gorm.io/gorm"
)

type txKeyType struct{} // ensures uniqueness of key (string key might have a collision)

var txKey = txKeyType{}

func withTx(ctx context.Context, tx *gorm.DB) context.Context {
	return context.WithValue(ctx, txKey, tx)
}

func FromWithDefault(ctx context.Context, defTx *gorm.DB) *gorm.DB {
	tx, ok := ctx.Value(txKey).(*gorm.DB)
	if !ok {
		return defTx
	}
	return tx
}

func From(ctx context.Context) (*gorm.DB, error) {
	tx, ok := ctx.Value(txKey).(*gorm.DB)
	if !ok {
		return nil, ErrNoTransaction.WithInternalMessage("tx key not found in context")
	}
	return tx, nil
}

package migration

import (
	"inventoryservice/infrastructure/idempotency/store/row"
	"inventoryservice/infrastructure/repository/entity"

	"gorm.io/gorm"
)

func Migrate(db *gorm.DB) error {
	return db.AutoMigrate(
		&entity.Product{},
		&entity.StockReservation{},
		&row.ProcessedKafkaMessageRow{},
		&row.HTTPRequestRow{},
	)
}

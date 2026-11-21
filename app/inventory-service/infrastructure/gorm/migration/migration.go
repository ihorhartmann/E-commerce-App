package migration

import (
	"inventoryservice/infrastructure/gorm/seed"
	seedentity "inventoryservice/infrastructure/gorm/seed/entity"
	"inventoryservice/infrastructure/idempotency/store/row"
	"inventoryservice/infrastructure/repository/entity"

	"gorm.io/gorm"
)

// Migrate brings the schema up to date, then inserts the demo catalog if this database hasn't had it yet.
func Migrate(db *gorm.DB) error {
	if err := db.AutoMigrate(
		&entity.Product{},
		&entity.StockReservation{},
		&row.ProcessedKafkaMessageRow{},
		&row.HTTPRequestRow{},
		&seedentity.SeedHistory{},
	); err != nil {
		return err
	}

	return seed.Apply(db)
}

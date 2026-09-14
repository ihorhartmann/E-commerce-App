package migration

import (
	row2 "shippingservice/adapter/out/idempotency/store/row"
	"shippingservice/adapter/out/repository/entity"

	"gorm.io/gorm"
)

func Migrate(db *gorm.DB) error {
	return db.AutoMigrate(
		&entity.Shipment{},
		&row2.ProcessedKafkaMessageRow{},
		&row2.HTTPRequestRow{},
	)
}

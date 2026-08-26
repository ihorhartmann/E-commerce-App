package migration

import (
	"shippingservice/adapter/out/repository/entity"

	"gorm.io/gorm"
)

func Migrate(db *gorm.DB) error {
	return db.AutoMigrate(&entity.Shipment{})
}

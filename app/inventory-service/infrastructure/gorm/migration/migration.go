package migration

import (
	"inventoryservice/infrastructure/repository/entity"

	"gorm.io/gorm"
)

func Migrate(db *gorm.DB) error {
	return db.AutoMigrate(&entity.Product{}, &entity.StockReservation{})
}

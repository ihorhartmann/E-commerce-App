package migration

import (
	idempotencyrow "shippingservice/adapter/out/idempotency/store/row"
	outboxrow "shippingservice/adapter/out/outbox/row"
	"shippingservice/adapter/out/repository/entity"

	"gorm.io/gorm"
)

func Migrate(db *gorm.DB) error {
	return db.AutoMigrate(
		&entity.Shipment{},
		&idempotencyrow.ProcessedKafkaMessageRow{},
		&idempotencyrow.HTTPRequestRow{},
		&outboxrow.OutboxMessage{},
	)
}

package row

import (
	"time"

	"github.com/google/uuid"
)

type ProcessedKafkaMessageRow struct {
	ID            uuid.UUID `gorm:"type:uuid;primaryKey"`
	CorrelationID string    `gorm:"not null"`
	MessageType   string    `gorm:"not null"`
	ProcessedAt   time.Time `gorm:"not null;index"`
}

func (ProcessedKafkaMessageRow) TableName() string {
	return "processed_kafka_messages"
}

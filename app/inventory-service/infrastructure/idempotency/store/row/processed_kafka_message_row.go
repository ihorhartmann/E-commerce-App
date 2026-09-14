package row

import "time"

type ProcessedKafkaMessageRow struct {
	ID            string    `gorm:"primaryKey"`
	CorrelationID string    `gorm:"not null"`
	MessageType   string    `gorm:"not null"`
	ProcessedAt   time.Time `gorm:"not null;index"`
}

func (ProcessedKafkaMessageRow) TableName() string {
	return "processed_kafka_messages"
}

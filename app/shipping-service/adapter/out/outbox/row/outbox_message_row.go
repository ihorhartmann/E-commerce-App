package row

import (
	"encoding/json"
	"time"

	"github.com/google/uuid"

	"shippingservice/application/port/out/outbox/dto"
)

type OutboxMessage struct {
	ID            uuid.UUID  `gorm:"primaryKey;type:uuid;default:uuidv7()"`
	CorrelationID string     `gorm:"not null"`
	MessageType   string     `gorm:"not null"`
	Key           string     `gorm:"not null"`
	Payload       string     `gorm:"type:jsonb;not null"`
	Traceparent   *string    `gorm:"type:varchar(55)"`
	SentAt        *time.Time `gorm:"index"`
	CreatedAt     time.Time  `gorm:"not null"`
}

func (OutboxMessage) TableName() string {
	return "outbox_messages"
}

func ToOutboxMessageRow(message dto.OutboxMessage) *OutboxMessage {
	return &OutboxMessage{
		ID:            message.MessageID,
		CorrelationID: message.CorrelationID,
		MessageType:   message.MessageType,
		Key:           message.Key,
		Payload:       string(message.Payload),
		Traceparent:   toNullableString(message.Traceparent),
	}
}

func FromOutboxMessageRow(row *OutboxMessage) dto.OutboxMessage {
	return dto.OutboxMessage{
		MessageID:     row.ID,
		CorrelationID: row.CorrelationID,
		MessageType:   row.MessageType,
		Key:           row.Key,
		Payload:       json.RawMessage(row.Payload),
		Traceparent:   fromNullableString(row.Traceparent),
	}
}

func toNullableString(value string) *string {
	if value == "" {
		return nil
	}
	return &value
}

func fromNullableString(value *string) string {
	if value == nil {
		return ""
	}
	return *value
}

package dto

import "github.com/google/uuid"

// KafkaMessageMetadata identifies a single Kafka message for idempotency purposes: MessageID is
// the per-message dedup key; CorrelationID and MessageType are carried through only for tracing.
type KafkaMessageMetadata struct {
	MessageID     uuid.UUID
	CorrelationID string
	MessageType   string
}

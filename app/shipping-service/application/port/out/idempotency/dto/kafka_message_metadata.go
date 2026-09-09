package dto

// KafkaMessageMetadata identifies a single Kafka message for idempotency purposes: MessageID is
// the per-message dedup key; CorrelationID and MessageType are carried through only for tracing.
type KafkaMessageMetadata struct {
	MessageID     string
	CorrelationID string
	MessageType   string
}

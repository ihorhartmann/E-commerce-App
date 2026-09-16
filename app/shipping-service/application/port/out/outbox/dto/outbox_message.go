package dto

import "encoding/json"

type OutboxMessage struct {
	MessageID     string
	CorrelationID string
	MessageType   string
	Key           string
	Payload       json.RawMessage
}

func NewOutboxMessage(correlationID string, messageType string, key string, payload any) (OutboxMessage, error) {
	value, err := json.Marshal(payload)
	if err != nil {
		return OutboxMessage{}, err
	}

	return OutboxMessage{
		CorrelationID: correlationID,
		MessageType:   messageType,
		Key:           key,
		Payload:       value,
	}, nil
}

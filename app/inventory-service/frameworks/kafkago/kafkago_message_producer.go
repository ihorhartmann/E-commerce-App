package kafkago

import (
	"inventoryservice/interfaceadapters/common/kafka"
	"inventoryservice/interfaceadapters/out/kafka/common"

	"github.com/google/uuid"
)

type messageProducer struct {
	common.KafkaMessageProducer
}

func NewStubMessageProducer() common.KafkaMessageProducer {
	return &messageProducer{}
}

func (p *messageProducer) Send(_ kafka.EventType, _ uuid.UUID, _ any) error {
	return nil
}

func (p *messageProducer) Close() error {
	return nil
}

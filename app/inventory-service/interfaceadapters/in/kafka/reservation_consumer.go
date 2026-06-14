package kafka

import (
	"inventoryservice/usecases"
	"inventoryservice/usecases/ports/kafka/common"

	"github.com/google/uuid"
)

type ReservationConsumer struct {
	consumer common.KafkaMessageConsumer
	useCase  *usecases.ReservationUseCase
}

func NewReservationConsumer(
	consumer common.KafkaMessageConsumer,
	useCase *usecases.ReservationUseCase,
) *ReservationConsumer {
	return &ReservationConsumer{
		consumer: consumer,
		useCase:  useCase,
	}
}

func (cc *ReservationConsumer) Start() {
	cc.consumer.ConsumeAsync(cc.handleMessage)
}

func (cc *ReservationConsumer) Close() error {
	return cc.consumer.Close()
}

func (cc *ReservationConsumer) handleMessage(eventType common.EventType, _, _ uuid.UUID, payload []byte) error {
	return nil
}

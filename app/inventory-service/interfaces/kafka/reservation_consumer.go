package kafka

import (
	"context"
	"encoding/json"
	"inventoryservice/application"
	"inventoryservice/application/ports/common/kafka"
	"inventoryservice/interfaces/kafka/common"
	"inventoryservice/interfaces/kafka/dto"
)

type ReservationConsumer struct {
	consumer            common.KafkaMessageConsumer
	reservationUseCases *application.ReservationServiceImpl
	productUseCases     *application.ProductServiceImpl
}

func NewReservationConsumer(
	consumer common.KafkaMessageConsumer,
	reservationUseCases *application.ReservationServiceImpl,
	productUseCases *application.ProductServiceImpl,
) *ReservationConsumer {
	return &ReservationConsumer{
		consumer:            consumer,
		reservationUseCases: reservationUseCases,
		productUseCases:     productUseCases,
	}
}

func (rc *ReservationConsumer) Start() {
	rc.consumer.ConsumeAsync(rc.handleMessage)
}

func (rc *ReservationConsumer) Close() error {
	return rc.consumer.Close()
}

func (rc *ReservationConsumer) handleMessage(messageType kafka.MessageType, _ string, _ string, payload []byte) error {
	switch messageType {
	case kafka.CancelReservationCommand:
		var command dto.CancelReservationCommand
		if err := json.Unmarshal(payload, &command); err != nil {
			return err
		}
		return rc.reservationUseCases.Cancel(context.Background(), command.OrderID)
	case kafka.FulfillReservationCommand:
		var command dto.FulfillReservationCommand
		if err := json.Unmarshal(payload, &command); err != nil {
			return err
		}
		return rc.reservationUseCases.Fulfill(context.Background(), command.OrderID)
	case kafka.HoldReservationCommand:
		var command dto.HoldReservationCommand
		if err := json.Unmarshal(payload, &command); err != nil {
			return err
		}
		return rc.reservationUseCases.HoldByCourier(context.Background(), command.OrderID)
	case kafka.RestockCommand:
		var command dto.RestockCommand
		if err := json.Unmarshal(payload, &command); err != nil {
			return err
		}
		return rc.productUseCases.Restock(context.Background(), dto.ToReservationDtos(command.Items))
	default:
		return nil
	}
}

package kafka

import (
	"context"
	"encoding/json"

	"inventoryservice/interfaceadapters/common/kafka"
	"inventoryservice/interfaceadapters/in/kafka/common"
	"inventoryservice/interfaceadapters/in/kafka/dto"
	"inventoryservice/usecases"
	usecasesdto "inventoryservice/usecases/dto"
)

type ReservationConsumer struct {
	consumer            common.KafkaMessageConsumer
	reservationUseCases *usecases.ReservationUseCases
	productUseCases     *usecases.ProductUseCases
}

func NewReservationConsumer(
	consumer common.KafkaMessageConsumer,
	reservationUseCases *usecases.ReservationUseCases,
	productUseCases *usecases.ProductUseCases,
) *ReservationConsumer {
	return &ReservationConsumer{
		consumer:            consumer,
		reservationUseCases: reservationUseCases,
		productUseCases:     productUseCases,
	}
}

func (cc *ReservationConsumer) Start() {
	cc.consumer.ConsumeAsync(cc.handleMessage)
}

func (cc *ReservationConsumer) Close() error {
	return cc.consumer.Close()
}

func (cc *ReservationConsumer) handleMessage(messageType kafka.MessageType, _ string, correlationID string, payload []byte) error {
	switch messageType {
	case kafka.CancelReservationCommand:
		var command dto.CancelReservationCommand
		if err := json.Unmarshal(payload, &command); err != nil {
			return err
		}
		return cc.reservationUseCases.Cancel(context.Background(), command.OrderID)
	case kafka.FulfillReservationCommand:
		var command dto.FulfillReservationCommand
		if err := json.Unmarshal(payload, &command); err != nil {
			return err
		}
		return cc.reservationUseCases.Fulfill(context.Background(), command.OrderID)
	case kafka.HoldReservationCommand:
		var command dto.HoldReservationCommand
		if err := json.Unmarshal(payload, &command); err != nil {
			return err
		}
		return cc.reservationUseCases.HoldByCourier(context.Background(), command.OrderID)
	case kafka.RestockCommand:
		var command dto.RestockCommand
		if err := json.Unmarshal(payload, &command); err != nil {
			return err
		}
		return cc.productUseCases.Restock(context.Background(), toReservationDtos(command.Items))
	default:
		return nil
	}
}

func toReservationDtos(items []dto.RestockItem) []usecasesdto.ReservationDto {
	reservationDtos := make([]usecasesdto.ReservationDto, 0, len(items))
	for _, item := range items {
		reservationDtos = append(reservationDtos, usecasesdto.ReservationDto{ProductID: item.ProductID, Quantity: item.Quantity})
	}

	return reservationDtos
}

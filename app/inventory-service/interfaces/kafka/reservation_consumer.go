package kafka

import (
	"context"
	"encoding/json"
	kafkaconst "inventoryservice/application/ports/out/kafka/common"

	"inventoryservice/application"
	applicationdto "inventoryservice/application/dto"
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

func (cc *ReservationConsumer) Start() {
	cc.consumer.ConsumeAsync(cc.handleMessage)
}

func (cc *ReservationConsumer) Close() error {
	return cc.consumer.Close()
}

func (cc *ReservationConsumer) handleMessage(messageType kafkaconst.MessageType, _ string, correlationID string, payload []byte) error {
	switch messageType {
	case kafkaconst.CancelReservationCommand:
		var command dto.CancelReservationCommand
		if err := json.Unmarshal(payload, &command); err != nil {
			return err
		}
		return cc.reservationUseCases.Cancel(context.Background(), command.OrderID)
	case kafkaconst.FulfillReservationCommand:
		var command dto.FulfillReservationCommand
		if err := json.Unmarshal(payload, &command); err != nil {
			return err
		}
		return cc.reservationUseCases.Fulfill(context.Background(), command.OrderID)
	case kafkaconst.HoldReservationCommand:
		var command dto.HoldReservationCommand
		if err := json.Unmarshal(payload, &command); err != nil {
			return err
		}
		return cc.reservationUseCases.HoldByCourier(context.Background(), command.OrderID)
	case kafkaconst.RestockCommand:
		var command dto.RestockCommand
		if err := json.Unmarshal(payload, &command); err != nil {
			return err
		}
		return cc.productUseCases.Restock(context.Background(), toReservationDtos(command.Items))
	default:
		return nil
	}
}

func toReservationDtos(items []dto.RestockItem) []applicationdto.ReservationDto {
	reservationDtos := make([]applicationdto.ReservationDto, 0, len(items))
	for _, item := range items {
		reservationDtos = append(reservationDtos, applicationdto.ReservationDto{ProductID: item.ProductID, Quantity: item.Quantity})
	}

	return reservationDtos
}

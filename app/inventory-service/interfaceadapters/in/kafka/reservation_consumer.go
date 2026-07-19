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
	consumer           common.KafkaMessageConsumer
	reservationUseCase *usecases.ReservationUseCase
	productUseCase     *usecases.ProductUseCase
}

func NewReservationConsumer(
	consumer common.KafkaMessageConsumer,
	reservationUseCase *usecases.ReservationUseCase,
	productUseCase *usecases.ProductUseCase,
) *ReservationConsumer {
	return &ReservationConsumer{
		consumer:           consumer,
		reservationUseCase: reservationUseCase,
		productUseCase:     productUseCase,
	}
}

func (cc *ReservationConsumer) Start() {
	cc.consumer.ConsumeAsync(cc.handleMessage)
}

func (cc *ReservationConsumer) Close() error {
	return cc.consumer.Close()
}

func (cc *ReservationConsumer) handleMessage(eventType kafka.EventType, _ string, correlationID string, payload []byte) error {
	switch eventType {
	case kafka.CancelReservationEventType:
		var message dto.CancelReservationMessage
		if err := json.Unmarshal(payload, &message); err != nil {
			return err
		}
		return cc.reservationUseCase.Cancel(context.Background(), message.OrderID)
	case kafka.FulfillReservationEventType:
		var message dto.FulfillReservationMessage
		if err := json.Unmarshal(payload, &message); err != nil {
			return err
		}
		return cc.reservationUseCase.Fulfill(context.Background(), message.OrderID)
	case kafka.HoldReservationEventType:
		var message dto.HoldReservationMessage
		if err := json.Unmarshal(payload, &message); err != nil {
			return err
		}
		return cc.reservationUseCase.HoldByCourier(context.Background(), message.OrderID)
	case kafka.RestockEventType:
		var message dto.RestockMessage
		if err := json.Unmarshal(payload, &message); err != nil {
			return err
		}
		return cc.productUseCase.Restock(context.Background(), toReservationDtos(message.Items))
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

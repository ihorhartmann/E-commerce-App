package kafka

import (
	"context"
	"encoding/json"
	"inventoryservice/application/port/common/kafka"
	"inventoryservice/application/port/in"
	idempotencydto "inventoryservice/application/port/out/idempotency/dto"
	"inventoryservice/interfaces/kafka/common"
	"inventoryservice/interfaces/kafka/dto"
)

type ReservationKafkaConsumer struct {
	consumer           common.KafkaMessageConsumer
	reservationService in.ReservationService
	productService     in.ProductService
}

func NewReservationKafkaConsumer(
	consumer common.KafkaMessageConsumer,
	reservationService in.ReservationService,
	productService in.ProductService,
) *ReservationKafkaConsumer {
	return &ReservationKafkaConsumer{
		consumer:           consumer,
		reservationService: reservationService,
		productService:     productService,
	}
}

func (rc *ReservationKafkaConsumer) StartAsync() {
	rc.consumer.ConsumeAsync(rc.handleMessage)
}

func (rc *ReservationKafkaConsumer) Close() error {
	return rc.consumer.Close()
}

func (rc *ReservationKafkaConsumer) handleMessage(messageType kafka.MessageType, messageID string, correlationID string, payload []byte) error {
	msg := idempotencydto.KafkaMessageMetadata{MessageID: messageID, CorrelationID: correlationID, MessageType: string(messageType)}

	switch messageType {
	case kafka.CancelReservationCommand:
		var command dto.CancelReservationCommand
		if err := json.Unmarshal(payload, &command); err != nil {
			return err
		}
		return rc.reservationService.Cancel(context.Background(), msg, command.OrderID)
	case kafka.FulfillReservationCommand:
		var command dto.FulfillReservationCommand
		if err := json.Unmarshal(payload, &command); err != nil {
			return err
		}
		return rc.reservationService.Fulfill(context.Background(), msg, command.OrderID)
	case kafka.HoldReservationCommand:
		var command dto.HoldReservationCommand
		if err := json.Unmarshal(payload, &command); err != nil {
			return err
		}
		return rc.reservationService.HoldByCourier(context.Background(), msg, command.OrderID)
	case kafka.RestockCommand:
		var command dto.RestockCommand
		if err := json.Unmarshal(payload, &command); err != nil {
			return err
		}
		return rc.productService.Restock(context.Background(), msg, dto.ToReservationDTOs(command.Items))
	default:
		return nil
	}
}

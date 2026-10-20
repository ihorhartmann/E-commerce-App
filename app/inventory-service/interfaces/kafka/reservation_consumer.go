package kafka

import (
	"context"
	"encoding/json"
	"inventoryservice/application/port/common/kafka"
	"inventoryservice/application/port/in"
	idempotencydto "inventoryservice/application/port/out/idempotency/dto"
	"inventoryservice/interfaces/kafka/common"
	"inventoryservice/interfaces/kafka/dto"
	"log/slog"

	"github.com/google/uuid"
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

func (rc *ReservationKafkaConsumer) handleMessage(ctx context.Context, messageType kafka.MessageType, messageID uuid.UUID, correlationID string, payload []byte) error {
	msg := idempotencydto.KafkaMessageMetadata{MessageID: messageID, CorrelationID: correlationID, MessageType: string(messageType)}

	switch messageType {
	case kafka.CancelReservationCommand:
		return rc.handleCancelReservation(ctx, msg, payload)
	case kafka.FulfillReservationCommand:
		return rc.handleFulfillReservation(ctx, msg, payload)
	case kafka.HoldReservationCommand:
		return rc.handleHoldReservation(ctx, msg, payload)
	case kafka.RestockCommand:
		return rc.handleRestock(ctx, msg, payload)
	default:
		return nil
	}
}

func (rc *ReservationKafkaConsumer) handleCancelReservation(ctx context.Context, msg idempotencydto.KafkaMessageMetadata, payload []byte) error {
	var command dto.CancelReservationCommand
	if err := json.Unmarshal(payload, &command); err != nil {
		return malformedPayloadErr(msg, err)
	}

	return rc.reservationService.Cancel(ctx, msg, command.OrderID)
}

func (rc *ReservationKafkaConsumer) handleFulfillReservation(ctx context.Context, msg idempotencydto.KafkaMessageMetadata, payload []byte) error {
	var command dto.FulfillReservationCommand
	if err := json.Unmarshal(payload, &command); err != nil {
		return malformedPayloadErr(msg, err)
	}

	return rc.reservationService.Fulfill(ctx, msg, command.OrderID)
}

func (rc *ReservationKafkaConsumer) handleHoldReservation(ctx context.Context, msg idempotencydto.KafkaMessageMetadata, payload []byte) error {
	var command dto.HoldReservationCommand
	if err := json.Unmarshal(payload, &command); err != nil {
		return malformedPayloadErr(msg, err)
	}

	return rc.reservationService.HoldByCourier(ctx, msg, command.OrderID)
}

func (rc *ReservationKafkaConsumer) handleRestock(ctx context.Context, msg idempotencydto.KafkaMessageMetadata, payload []byte) error {
	var command dto.RestockCommand
	if err := json.Unmarshal(payload, &command); err != nil {
		return malformedPayloadErr(msg, err)
	}

	return rc.productService.Restock(ctx, msg, dto.ToReservationDTOs(command.Items))
}

// malformedPayloadErr reports a payload that doesn't decode - permanent, so the message is dead-lettered at once.
func malformedPayloadErr(msg idempotencydto.KafkaMessageMetadata, err error) error {
	return ErrMalformedMessage.
		WithAttrs(slog.String("messageType", msg.MessageType), slog.String("messageID", msg.MessageID.String())).
		WithCause(err)
}

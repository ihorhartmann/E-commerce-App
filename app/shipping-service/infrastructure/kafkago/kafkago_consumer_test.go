package kafkago

import (
	"testing"

	"shippingservice/adapter/common/kafka"

	kafkago "github.com/segmentio/kafka-go"
)

func TestParseHeaders(t *testing.T) {
	t.Run("extracts messageType, messageId and correlationId when all headers are present", func(t *testing.T) {
		wantMessageType := kafka.CreateShipmentCommand
		wantMessageID := "msg-1"
		wantCorrelationID := "corr-1"
		headers := []kafkago.Header{
			{Key: kafka.MessageTypeHeader, Value: []byte(wantMessageType.String())},
			{Key: kafka.MessageIDHeader, Value: []byte(wantMessageID)},
			{Key: kafka.CorrelationIDHeader, Value: []byte(wantCorrelationID)},
		}

		gotMessageType, gotMessageID, gotCorrelationID, err := parseHeaders(headers)
		if err != nil {
			t.Fatalf("err = %v, want nil", err)
		}
		if gotMessageType != wantMessageType {
			t.Errorf("messageType = %v, want %v", gotMessageType, wantMessageType)
		}
		if gotMessageID != wantMessageID {
			t.Errorf("messageID = %v, want %v", gotMessageID, wantMessageID)
		}
		if gotCorrelationID != wantCorrelationID {
			t.Errorf("correlationID = %v, want %v", gotCorrelationID, wantCorrelationID)
		}
	})

	t.Run("ignores unknown headers while extracting the required ones", func(t *testing.T) {
		wantMessageType := kafka.CreateShipmentCommand
		wantMessageID := "msg-1"
		wantCorrelationID := "corr-1"
		headers := []kafkago.Header{
			{Key: "someOtherHeader", Value: []byte("ignored")},
			{Key: kafka.MessageTypeHeader, Value: []byte(wantMessageType.String())},
			{Key: kafka.MessageIDHeader, Value: []byte(wantMessageID)},
			{Key: kafka.CorrelationIDHeader, Value: []byte(wantCorrelationID)},
		}

		gotMessageType, gotMessageID, gotCorrelationID, err := parseHeaders(headers)
		if err != nil {
			t.Fatalf("err = %v, want nil", err)
		}
		if gotMessageType != wantMessageType {
			t.Errorf("messageType = %v, want %v", gotMessageType, wantMessageType)
		}
		if gotMessageID != wantMessageID {
			t.Errorf("messageID = %v, want %v", gotMessageID, wantMessageID)
		}
		if gotCorrelationID != wantCorrelationID {
			t.Errorf("correlationID = %v, want %v", gotCorrelationID, wantCorrelationID)
		}
	})

	t.Run("returns an error when the messageType header is missing", func(t *testing.T) {
		headers := []kafkago.Header{
			{Key: kafka.MessageIDHeader, Value: []byte("msg-1")},
			{Key: kafka.CorrelationIDHeader, Value: []byte("corr-1")},
		}

		if _, _, _, err := parseHeaders(headers); err == nil {
			t.Error("err = nil, want an error")
		}
	})

	t.Run("returns an error when the messageId header is missing", func(t *testing.T) {
		headers := []kafkago.Header{
			{Key: kafka.MessageTypeHeader, Value: []byte(kafka.CreateShipmentCommand.String())},
			{Key: kafka.CorrelationIDHeader, Value: []byte("corr-1")},
		}

		if _, _, _, err := parseHeaders(headers); err == nil {
			t.Error("err = nil, want an error")
		}
	})

	t.Run("returns an error when the correlationId header is missing", func(t *testing.T) {
		headers := []kafkago.Header{
			{Key: kafka.MessageTypeHeader, Value: []byte(kafka.CreateShipmentCommand.String())},
			{Key: kafka.MessageIDHeader, Value: []byte("msg-1")},
		}

		if _, _, _, err := parseHeaders(headers); err == nil {
			t.Error("err = nil, want an error")
		}
	})

	t.Run("returns an error when no headers are present", func(t *testing.T) {
		if _, _, _, err := parseHeaders(nil); err == nil {
			t.Error("err = nil, want an error")
		}
	})
}

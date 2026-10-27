package kafkago

import (
	"slices"
	"testing"

	kafkago "github.com/segmentio/kafka-go"
)

func TestHeaderCarrier_Get(t *testing.T) {
	t.Run("returns the value of the header with the key", func(t *testing.T) {
		want := "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
		headers := []kafkago.Header{{Key: "messageType", Value: []byte("CancelReservation")}, {Key: traceparentHeader, Value: []byte(want)}}

		got := newHeaderCarrier(&headers).Get(traceparentHeader)

		if got != want {
			t.Errorf("Get = %q, want %q", got, want)
		}
	})

	t.Run("returns an empty string when there is no header with the key", func(t *testing.T) {
		headers := []kafkago.Header{{Key: "messageType", Value: []byte("CancelReservation")}}

		got := newHeaderCarrier(&headers).Get(traceparentHeader)

		if got != "" {
			t.Errorf("Get = %q, want empty", got)
		}
	})
}

func TestHeaderCarrier_Set(t *testing.T) {
	t.Run("adds a header when there is none with the key", func(t *testing.T) {
		value := "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
		headers := []kafkago.Header{{Key: "messageType", Value: []byte("CancelReservation")}}

		newHeaderCarrier(&headers).Set(traceparentHeader, value)

		if wantLen := 2; len(headers) != wantLen {
			t.Fatalf("headers = %d, want %d", len(headers), wantLen)
		}
		if got := string(headers[1].Value); got != value {
			t.Errorf("traceparent = %q, want %q", got, value)
		}
	})

	t.Run("replaces the header with the key instead of adding a second one", func(t *testing.T) {
		value := "00-5bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
		headers := []kafkago.Header{{Key: traceparentHeader, Value: []byte("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")}}

		newHeaderCarrier(&headers).Set(traceparentHeader, value)

		if wantLen := 1; len(headers) != wantLen {
			t.Fatalf("headers = %d, want %d", len(headers), wantLen)
		}
		if got := string(headers[0].Value); got != value {
			t.Errorf("traceparent = %q, want %q", got, value)
		}
	})
}

func TestHeaderCarrier_Keys(t *testing.T) {
	t.Run("returns the key of every header, in order", func(t *testing.T) {
		headers := []kafkago.Header{{Key: "messageType"}, {Key: traceparentHeader}}

		got := newHeaderCarrier(&headers).Keys()

		if want := []string{"messageType", traceparentHeader}; !slices.Equal(got, want) {
			t.Errorf("Keys = %v, want %v", got, want)
		}
	})
}

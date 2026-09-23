package model

import (
	"errors"
	"testing"

	"inventoryservice/domain"
)

func TestNewStockReservation(t *testing.T) {
	t.Run("creates a reservation with the given fields", func(t *testing.T) {
		id := uint64(1)
		productID := uint64(10)
		orderID := uint64(100)
		quantity := uint(5)
		reservation, err := NewStockReservation(id, productID, orderID, quantity, ReservationHolderBuyer)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if reservation.ID() != id {
			t.Errorf("ID() = %d, want %d", reservation.ID(), id)
		}
		if reservation.ProductID() != productID {
			t.Errorf("ProductID() = %d, want %d", reservation.ProductID(), productID)
		}
		if reservation.OrderID() != orderID {
			t.Errorf("OrderID() = %d, want %d", reservation.OrderID(), orderID)
		}
		if reservation.Quantity() != quantity {
			t.Errorf("Quantity() = %d, want %d", reservation.Quantity(), quantity)
		}
		if reservation.Holder() != ReservationHolderBuyer {
			t.Errorf("Holder() = %v, want %v", reservation.Holder(), ReservationHolderBuyer)
		}
	})

	t.Run("rejects a zero productId", func(t *testing.T) {
		_, err := NewStockReservation(1, 0, 100, 5, ReservationHolderBuyer)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("rejects a zero orderId", func(t *testing.T) {
		_, err := NewStockReservation(1, 10, 0, 5, ReservationHolderBuyer)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("rejects a zero quantity", func(t *testing.T) {
		_, err := NewStockReservation(1, 10, 100, 0, ReservationHolderBuyer)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("rejects an invalid holder", func(t *testing.T) {
		_, err := NewStockReservation(1, 10, 100, 5, "SOMEWHERE_ELSE")
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})
}

func TestStockReservation_WithHolder(t *testing.T) {
	t.Run("returns a copy held by the new holder", func(t *testing.T) {
		reservation, _ := NewStockReservation(1, 10, 100, 5, ReservationHolderBuyer)

		held, err := reservation.UpdateHolder(ReservationHolderCourier)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if held.Holder() != ReservationHolderCourier {
			t.Errorf("Holder() = %v, want %v", held.Holder(), ReservationHolderCourier)
		}
		if held.ID() != reservation.ID() || held.ProductID() != reservation.ProductID() ||
			held.OrderID() != reservation.OrderID() || held.Quantity() != reservation.Quantity() {
			t.Errorf("WithHolder changed unrelated fields: got %+v", held)
		}
	})

	t.Run("rejects an invalid holder", func(t *testing.T) {
		reservation, _ := NewStockReservation(1, 10, 100, 5, ReservationHolderBuyer)

		_, err := reservation.UpdateHolder("SOMEWHERE_ELSE")
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})
}

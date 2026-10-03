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

func TestNewExistingStockReservation(t *testing.T) {
	t.Run("creates a reservation with the given id and version", func(t *testing.T) {
		id := uint64(1)
		version := uint64(7)
		reservation, err := NewExistingStockReservation(id, 10, 100, 5, ReservationHolderCourier, version)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if reservation.ID() != id || reservation.Version() != version {
			t.Errorf("ID() = %d, Version() = %d, want %d and %d", reservation.ID(), reservation.Version(), id, version)
		}
	})

	t.Run("rejects a zero id", func(t *testing.T) {
		_, err := NewExistingStockReservation(0, 10, 100, 5, ReservationHolderBuyer, 0)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("rejects a zero quantity, like a new reservation", func(t *testing.T) {
		_, err := NewExistingStockReservation(1, 10, 100, 0, ReservationHolderBuyer, 0)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})
}

func TestStockReservation_HoldByCourier(t *testing.T) {
	t.Run("hands the reservation over from the buyer to the courier", func(t *testing.T) {
		reservation, _ := NewStockReservation(1, 10, 100, 5, ReservationHolderBuyer)

		err := reservation.HoldByCourier()
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if reservation.Holder() != ReservationHolderCourier {
			t.Errorf("Holder() = %v, want %v", reservation.Holder(), ReservationHolderCourier)
		}
	})

	t.Run("rejects a reservation already held by the courier", func(t *testing.T) {
		reservation, _ := NewStockReservation(1, 10, 100, 5, ReservationHolderCourier)

		err := reservation.HoldByCourier()
		if !errors.Is(err, ErrInvalidHolderTransition) {
			t.Errorf("err = %v, want %v", err, ErrInvalidHolderTransition)
		}
	})
}

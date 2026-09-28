package model

import (
	"errors"
	"testing"
)

func newTestProductStock(t *testing.T, quantity uint, reservations ...*StockReservation) *ProductStock {
	t.Helper()
	stock, err := NewProductStock(newTestProduct(t, quantity), reservations)
	if err != nil {
		t.Fatalf("unexpected error building test product stock: %v", err)
	}
	return stock
}

func TestNewProductStock(t *testing.T) {
	t.Run("holds the product and the given reservations", func(t *testing.T) {
		product := newTestProduct(t, 10)
		reservation, _ := NewStockReservation(1, product.ID(), 100, 4, ReservationHolderBuyer)

		stock, err := NewProductStock(product, []*StockReservation{reservation})
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if stock.Product() != product {
			t.Errorf("Product() = %+v, want %+v", stock.Product(), product)
		}
		if len(stock.Reservations()) != 1 || stock.Reservations()[0] != reservation {
			t.Errorf("Reservations() = %v, want [%v]", stock.Reservations(), reservation)
		}
	})

	t.Run("rejects a reservation of another product", func(t *testing.T) {
		product := newTestProduct(t, 10)
		reservation, _ := NewStockReservation(1, product.ID()+1, 100, 4, ReservationHolderBuyer)

		_, err := NewProductStock(product, []*StockReservation{reservation})
		if !errors.Is(err, ErrForeignReservation) {
			t.Errorf("err = %v, want %v", err, ErrForeignReservation)
		}
	})
}

func TestProductStock_Reserved(t *testing.T) {
	t.Run("is zero with no reservations", func(t *testing.T) {
		stock := newTestProductStock(t, 10)

		if stock.Reserved() != 0 {
			t.Errorf("Reserved() = %d, want 0", stock.Reserved())
		}
	})

	t.Run("sums the quantity across all reservations", func(t *testing.T) {
		r1, _ := NewStockReservation(1, 1, 100, 3, ReservationHolderBuyer)
		r2, _ := NewStockReservation(2, 1, 101, 2, ReservationHolderCourier)
		stock := newTestProductStock(t, 10, r1, r2)

		if stock.Reserved() != 5 {
			t.Errorf("Reserved() = %d, want 5", stock.Reserved())
		}
	})
}

func TestProductStock_Available(t *testing.T) {
	t.Run("subtracts reserved from on-hand quantity", func(t *testing.T) {
		reservation, _ := NewStockReservation(1, 1, 100, 4, ReservationHolderBuyer)
		stock := newTestProductStock(t, 10, reservation)

		if stock.Available() != 6 {
			t.Errorf("Available() = %d, want 6", stock.Available())
		}
	})

	t.Run("is zero, not negative, when reserved exceeds on-hand quantity", func(t *testing.T) {
		reservation, _ := NewStockReservation(1, 1, 100, 8, ReservationHolderBuyer)
		stock := newTestProductStock(t, 5, reservation)

		if stock.Available() != 0 {
			t.Errorf("Available() = %d, want 0", stock.Available())
		}
	})
}

func TestProductStock_CreateReservation(t *testing.T) {
	t.Run("creates a reservation when enough stock is available", func(t *testing.T) {
		orderID := uint64(100)
		quantity := uint(4)
		stock := newTestProductStock(t, 10)

		reservedStock, reservation, err := stock.CreateReservation(orderID, quantity)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if reservedStock.Available() != 6 {
			t.Errorf("returned stock Available() = %d, want 6", reservedStock.Available())
		}
		if stock.Available() != 10 {
			t.Errorf("original stock Available() = %d, want 10 (unchanged)", stock.Available())
		}
		if reservation.ProductID() != stock.Product().ID() {
			t.Errorf("ProductID() = %d, want %d", reservation.ProductID(), stock.Product().ID())
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

	t.Run("succeeds when requesting exactly the available quantity", func(t *testing.T) {
		existing, _ := NewStockReservation(1, 1, 100, 4, ReservationHolderBuyer)
		stock := newTestProductStock(t, 10, existing)

		if _, _, err := stock.CreateReservation(101, 6); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
	})

	t.Run("fails when requesting one more than available", func(t *testing.T) {
		existing, _ := NewStockReservation(1, 1, 100, 4, ReservationHolderBuyer)
		stock := newTestProductStock(t, 10, existing)

		_, _, err := stock.CreateReservation(101, 7)
		if !errors.Is(err, ErrInsufficientStock) {
			t.Errorf("err = %v, want %v", err, ErrInsufficientStock)
		}
	})

	t.Run("counts a reservation it just created against a later one on the returned stock", func(t *testing.T) {
		orderID := uint64(100)
		stock := newTestProductStock(t, 10)

		reservedStock, _, err := stock.CreateReservation(orderID, 6)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		_, _, err = reservedStock.CreateReservation(orderID, 5)
		if !errors.Is(err, ErrInsufficientStock) {
			t.Errorf("err = %v, want %v", err, ErrInsufficientStock)
		}
	})

	t.Run("fails when the product has no available stock", func(t *testing.T) {
		stock := newTestProductStock(t, 0)

		_, _, err := stock.CreateReservation(100, 1)
		if !errors.Is(err, ErrInsufficientStock) {
			t.Errorf("err = %v, want %v", err, ErrInsufficientStock)
		}
	})
}

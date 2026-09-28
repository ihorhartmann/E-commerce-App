package model

import (
	"errors"
	"testing"

	"inventoryservice/domain"
	"inventoryservice/domain/values"
)

func testMoney(t *testing.T) values.Money {
	t.Helper()
	money, err := values.NewMoney(10.0, values.USD)
	if err != nil {
		t.Fatalf("unexpected error building test money: %v", err)
	}
	return money
}

func newTestProduct(t *testing.T, quantity uint) *Product {
	t.Helper()
	product, err := NewProduct(1, "Widget", "a widget", testMoney(t), quantity)
	if err != nil {
		t.Fatalf("unexpected error building test product: %v", err)
	}
	return product
}

func TestNewProduct(t *testing.T) {
	t.Run("creates a product with the given fields", func(t *testing.T) {
		name := "Widget"
		quantity := uint(10)
		product, err := NewProduct(1, name, "a widget", testMoney(t), quantity)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if product.ID() != 1 {
			t.Errorf("ID() = %d, want 1", product.ID())
		}
		if product.Name() != name {
			t.Errorf("Name() = %q, want %q", product.Name(), name)
		}
		if product.Quantity() != quantity {
			t.Errorf("Quantity() = %d, want %d", product.Quantity(), quantity)
		}
	})

	t.Run("rejects a blank name", func(t *testing.T) {
		_, err := NewProduct(1, "  ", "a widget", testMoney(t), 10)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})
}

func TestNewExistingProduct(t *testing.T) {
	t.Run("rejects a zero id", func(t *testing.T) {
		_, err := NewExistingProduct(0, "Widget", "a widget", testMoney(t), 10)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("creates a product when id is set", func(t *testing.T) {
		id := uint64(1)
		product, err := NewExistingProduct(id, "Widget", "a widget", testMoney(t), 10)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if product.ID() != id {
			t.Errorf("ID() = %d, want %d", product.ID(), id)
		}
	})

	t.Run("rejects a blank name even with an id set", func(t *testing.T) {
		_, err := NewExistingProduct(1, "", "a widget", testMoney(t), 10)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})
}

func TestProduct_Fulfill(t *testing.T) {
	t.Run("removes the reservation's quantity from on-hand stock", func(t *testing.T) {
		product := newTestProduct(t, 10)
		reservation, _ := NewStockReservation(1, product.ID(), 100, 4, ReservationHolderCourier)

		fulfilled, err := product.Fulfill(reservation)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if fulfilled.Quantity() != 6 {
			t.Errorf("Quantity() = %d, want 6", fulfilled.Quantity())
		}
		if fulfilled.ID() != product.ID() || fulfilled.Name() != product.Name() {
			t.Errorf("Fulfill changed unrelated fields: got %+v", fulfilled)
		}
	})

	t.Run("rejects a reservation of another product", func(t *testing.T) {
		product := newTestProduct(t, 10)
		reservation, _ := NewStockReservation(1, product.ID()+1, 100, 4, ReservationHolderCourier)

		_, err := product.Fulfill(reservation)
		if !errors.Is(err, ErrForeignReservation) {
			t.Errorf("err = %v, want %v", err, ErrForeignReservation)
		}
	})

	t.Run("rejects a reservation not handed over to a courier yet", func(t *testing.T) {
		product := newTestProduct(t, 10)
		reservation, _ := NewStockReservation(1, product.ID(), 100, 4, ReservationHolderBuyer)

		_, err := product.Fulfill(reservation)
		if !errors.Is(err, ErrReservationNotHeldByCourier) {
			t.Errorf("err = %v, want %v", err, ErrReservationNotHeldByCourier)
		}
	})

	t.Run("rejects a reservation of more than is on hand", func(t *testing.T) {
		product := newTestProduct(t, 3)
		reservation, _ := NewStockReservation(1, product.ID(), 100, 4, ReservationHolderCourier)

		_, err := product.Fulfill(reservation)
		if !errors.Is(err, ErrInsufficientStock) {
			t.Errorf("err = %v, want %v", err, ErrInsufficientStock)
		}
	})
}

func TestProduct_Restock(t *testing.T) {
	product := newTestProduct(t, 10)

	restocked := product.Restock(4)

	if restocked.Quantity() != 14 {
		t.Errorf("Quantity() = %d, want 14", restocked.Quantity())
	}
	if restocked.ID() != product.ID() || restocked.Name() != product.Name() {
		t.Errorf("Restock changed unrelated fields: got %+v", restocked)
	}
}

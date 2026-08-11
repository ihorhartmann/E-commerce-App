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
		product, err := NewProduct(1, "Widget", "a widget", testMoney(t), 10)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if product.ID() != 1 {
			t.Errorf("ID() = %d, want 1", product.ID())
		}
		if product.Name() != "Widget" {
			t.Errorf("Name() = %q, want %q", product.Name(), "Widget")
		}
		if product.Quantity() != 10 {
			t.Errorf("Quantity() = %d, want 10", product.Quantity())
		}
		if len(product.StockReservations()) != 0 {
			t.Errorf("StockReservations() = %v, want empty", product.StockReservations())
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
		product, err := NewExistingProduct(1, "Widget", "a widget", testMoney(t), 10)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if product.ID() != 1 {
			t.Errorf("ID() = %d, want 1", product.ID())
		}
	})

	t.Run("rejects a blank name even with an id set", func(t *testing.T) {
		_, err := NewExistingProduct(1, "", "a widget", testMoney(t), 10)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})
}

func TestProduct_Reserved(t *testing.T) {
	t.Run("is zero with no reservations", func(t *testing.T) {
		product := newTestProduct(t, 10)

		if product.Reserved() != 0 {
			t.Errorf("Reserved() = %d, want 0", product.Reserved())
		}
	})

	t.Run("sums the quantity across all reservations", func(t *testing.T) {
		product := newTestProduct(t, 10)
		r1, _ := NewStockReservation(1, product.ID(), 100, 3, ReservationHolderBuyer)
		r2, _ := NewStockReservation(2, product.ID(), 101, 2, ReservationHolderCourier)
		productWithReservations := product.WithStockReservations([]*StockReservation{r1, r2})

		if productWithReservations.Reserved() != 5 {
			t.Errorf("Reserved() = %d, want 5", productWithReservations.Reserved())
		}
	})
}

func TestProduct_Available(t *testing.T) {
	t.Run("subtracts reserved from on-hand quantity", func(t *testing.T) {
		product := newTestProduct(t, 10)
		reservation, _ := NewStockReservation(1, product.ID(), 100, 4, ReservationHolderBuyer)
		productWithReservations := product.WithStockReservations([]*StockReservation{reservation})

		if productWithReservations.Available() != 6 {
			t.Errorf("Available() = %d, want 6", productWithReservations.Available())
		}
	})

	t.Run("is zero, not negative, when reserved exceeds on-hand quantity", func(t *testing.T) {
		product := newTestProduct(t, 5)
		reservation, _ := NewStockReservation(1, product.ID(), 100, 8, ReservationHolderBuyer)
		productWithReservations := product.WithStockReservations([]*StockReservation{reservation})

		if productWithReservations.Available() != 0 {
			t.Errorf("Available() = %d, want 0", productWithReservations.Available())
		}
	})
}

func TestProduct_WithStockReservations(t *testing.T) {
	product := newTestProduct(t, 10)
	reservation, _ := NewStockReservation(1, product.ID(), 100, 4, ReservationHolderBuyer)

	productWithReservations := product.WithStockReservations([]*StockReservation{reservation})

	if len(productWithReservations.StockReservations()) != 1 {
		t.Errorf("StockReservations() length = %d, want 1", len(productWithReservations.StockReservations()))
	}
	if productWithReservations.ID() != product.ID() || productWithReservations.Name() != product.Name() || productWithReservations.Quantity() != product.Quantity() {
		t.Errorf("WithStockReservations changed unrelated fields: got %+v", productWithReservations)
	}
}

func TestProduct_CreateReservation(t *testing.T) {
	t.Run("creates a reservation when enough stock is available", func(t *testing.T) {
		product := newTestProduct(t, 10)

		reservation, err := product.CreateReservation(100, 4, ReservationHolderBuyer)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if reservation.ProductID() != product.ID() {
			t.Errorf("ProductID() = %d, want %d", reservation.ProductID(), product.ID())
		}
		if reservation.OrderID() != 100 {
			t.Errorf("OrderID() = %d, want 100", reservation.OrderID())
		}
		if reservation.Quantity() != 4 {
			t.Errorf("Quantity() = %d, want 4", reservation.Quantity())
		}
		if reservation.Holder() != ReservationHolderBuyer {
			t.Errorf("Holder() = %v, want %v", reservation.Holder(), ReservationHolderBuyer)
		}
	})

	t.Run("succeeds when requesting exactly the available quantity", func(t *testing.T) {
		product := newTestProduct(t, 10)
		existingReservations, _ := NewStockReservation(1, product.ID(), 100, 4, ReservationHolderBuyer)
		productWithReservations := product.WithStockReservations([]*StockReservation{existingReservations})

		if _, err := productWithReservations.CreateReservation(101, 6, ReservationHolderBuyer); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
	})

	t.Run("fails when requesting one more than available", func(t *testing.T) {
		product := newTestProduct(t, 10)
		existingReservations, _ := NewStockReservation(1, product.ID(), 100, 4, ReservationHolderBuyer)
		productWithReservations := product.WithStockReservations([]*StockReservation{existingReservations})

		_, err := productWithReservations.CreateReservation(101, 7, ReservationHolderBuyer)
		if !errors.Is(err, ErrInsufficientStock) {
			t.Errorf("err = %v, want %v", err, ErrInsufficientStock)
		}
	})

	t.Run("fails when the product has no available stock", func(t *testing.T) {
		product := newTestProduct(t, 0)

		_, err := product.CreateReservation(100, 1, ReservationHolderBuyer)
		if !errors.Is(err, ErrInsufficientStock) {
			t.Errorf("err = %v, want %v", err, ErrInsufficientStock)
		}
	})
}

func TestProduct_Fulfill(t *testing.T) {
	product := newTestProduct(t, 10)

	fulfilled := product.Fulfill(4)

	if fulfilled.Quantity() != 6 {
		t.Errorf("Quantity() = %d, want 6", fulfilled.Quantity())
	}
	if fulfilled.ID() != product.ID() || fulfilled.Name() != product.Name() {
		t.Errorf("Fulfill changed unrelated fields: got %+v", fulfilled)
	}
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

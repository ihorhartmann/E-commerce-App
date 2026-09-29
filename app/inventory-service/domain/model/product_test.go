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

// newTestProduct builds a stored product with quantity on hand, of which reserved is reserved.
func newTestProduct(t *testing.T, quantity, reserved uint) *Product {
	t.Helper()
	product, err := NewExistingProduct(1, "Widget", "a widget", testMoney(t), quantity, reserved, 3)
	if err != nil {
		t.Fatalf("unexpected error building test product: %v", err)
	}
	return product
}

func TestNewProduct(t *testing.T) {
	t.Run("creates a product with nothing reserved", func(t *testing.T) {
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
		if product.Reserved() != 0 || product.Version() != 0 {
			t.Errorf("Reserved() = %d, Version() = %d, want both 0", product.Reserved(), product.Version())
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
	t.Run("creates a product with the given reserved quantity and version", func(t *testing.T) {
		id := uint64(1)
		reserved := uint(4)
		version := uint64(7)
		product, err := NewExistingProduct(id, "Widget", "a widget", testMoney(t), 10, reserved, version)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if product.ID() != id || product.Reserved() != reserved || product.Version() != version {
			t.Errorf("got %+v, want id %d, reserved %d, version %d", product, id, reserved, version)
		}
	})

	t.Run("rejects a zero id", func(t *testing.T) {
		_, err := NewExistingProduct(0, "Widget", "a widget", testMoney(t), 10, 0, 0)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("rejects more reserved than on hand", func(t *testing.T) {
		_, err := NewExistingProduct(1, "Widget", "a widget", testMoney(t), 5, 6, 0)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("rejects a blank name even with an id set", func(t *testing.T) {
		_, err := NewExistingProduct(1, "", "a widget", testMoney(t), 10, 0, 0)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})
}

func TestProduct_Available(t *testing.T) {
	product := newTestProduct(t, 10, 4)

	if product.Available() != 6 {
		t.Errorf("Available() = %d, want 6", product.Available())
	}
}

func TestProduct_ValidateDeletion(t *testing.T) {
	t.Run("allows deleting a product with nothing reserved", func(t *testing.T) {
		if err := newTestProduct(t, 10, 0).ValidateDeletion(); err != nil {
			t.Errorf("err = %v, want nil", err)
		}
	})

	t.Run("rejects deleting a product with reserved stock", func(t *testing.T) {
		err := newTestProduct(t, 10, 1).ValidateDeletion()
		if !errors.Is(err, ErrProductReserved) {
			t.Errorf("err = %v, want %v", err, ErrProductReserved)
		}
	})
}

func TestProduct_Update(t *testing.T) {
	t.Run("replaces the details, keeping reserved and version", func(t *testing.T) {
		name := "Gadget"
		quantity := uint(20)
		product := newTestProduct(t, 10, 4)

		updated, err := product.Update(name, "a gadget", testMoney(t), quantity)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if updated.Name() != name || updated.Quantity() != quantity {
			t.Errorf("got %+v, want name %q and quantity %d", updated, name, quantity)
		}
		if updated.Reserved() != product.Reserved() || updated.Version() != product.Version() {
			t.Errorf("Update changed reserved or version: got %+v", updated)
		}
	})

	t.Run("rejects a quantity below what is reserved", func(t *testing.T) {
		product := newTestProduct(t, 10, 4)

		_, err := product.Update("Widget", "a widget", testMoney(t), 3)
		if !errors.Is(err, ErrQuantityBelowReserved) {
			t.Errorf("err = %v, want %v", err, ErrQuantityBelowReserved)
		}
	})

	t.Run("rejects a blank name", func(t *testing.T) {
		product := newTestProduct(t, 10, 4)

		_, err := product.Update(" ", "a widget", testMoney(t), 10)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})
}

func TestProduct_Reserve(t *testing.T) {
	t.Run("creates a buyer-held reservation and counts it as reserved", func(t *testing.T) {
		orderID := uint64(100)
		quantity := uint(4)
		product := newTestProduct(t, 10, 2)

		reservedProduct, reservation, err := product.Reserve(orderID, quantity)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if reservedProduct.Reserved() != 6 || reservedProduct.Quantity() != 10 {
			t.Errorf("returned product reserved = %d, quantity = %d, want 6 and 10", reservedProduct.Reserved(), reservedProduct.Quantity())
		}
		if product.Reserved() != 2 {
			t.Errorf("original product Reserved() = %d, want 2 (unchanged)", product.Reserved())
		}
		if reservation.ProductID() != product.ID() || reservation.OrderID() != orderID || reservation.Quantity() != quantity {
			t.Errorf("unexpected reservation: %+v", reservation)
		}
		if reservation.Holder() != ReservationHolderBuyer {
			t.Errorf("Holder() = %v, want %v", reservation.Holder(), ReservationHolderBuyer)
		}
	})

	t.Run("succeeds when requesting exactly the available quantity", func(t *testing.T) {
		product := newTestProduct(t, 10, 4)

		if _, _, err := product.Reserve(101, 6); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
	})

	t.Run("fails when requesting one more than available", func(t *testing.T) {
		product := newTestProduct(t, 10, 4)

		_, _, err := product.Reserve(101, 7)
		if !errors.Is(err, ErrInsufficientStock) {
			t.Errorf("err = %v, want %v", err, ErrInsufficientStock)
		}
	})

	t.Run("counts a reservation it just made against a later one on the returned product", func(t *testing.T) {
		orderID := uint64(100)
		product := newTestProduct(t, 10, 0)

		reservedProduct, _, err := product.Reserve(orderID, 6)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		_, _, err = reservedProduct.Reserve(orderID, 5)
		if !errors.Is(err, ErrInsufficientStock) {
			t.Errorf("err = %v, want %v", err, ErrInsufficientStock)
		}
	})
}

func TestProduct_ReleaseReservation(t *testing.T) {
	t.Run("stops counting the reservation as reserved, keeping the quantity on hand", func(t *testing.T) {
		product := newTestProduct(t, 10, 6)
		reservation, _ := NewStockReservation(1, product.ID(), 100, 4, ReservationHolderBuyer)

		released, err := product.ReleaseReservation(reservation)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if released.Reserved() != 2 || released.Quantity() != 10 {
			t.Errorf("reserved = %d, quantity = %d, want 2 and 10", released.Reserved(), released.Quantity())
		}
	})

	t.Run("rejects a reservation of another product", func(t *testing.T) {
		product := newTestProduct(t, 10, 6)
		reservation, _ := NewStockReservation(1, product.ID()+1, 100, 4, ReservationHolderBuyer)

		_, err := product.ReleaseReservation(reservation)
		if !errors.Is(err, ErrForeignReservation) {
			t.Errorf("err = %v, want %v", err, ErrForeignReservation)
		}
	})

	t.Run("rejects a reservation of more than the product counts as reserved", func(t *testing.T) {
		product := newTestProduct(t, 10, 3)
		reservation, _ := NewStockReservation(1, product.ID(), 100, 4, ReservationHolderBuyer)

		_, err := product.ReleaseReservation(reservation)
		if !errors.Is(err, ErrReservedQuantityMismatch) {
			t.Errorf("err = %v, want %v", err, ErrReservedQuantityMismatch)
		}
	})
}

func TestProduct_Fulfill(t *testing.T) {
	t.Run("removes the reservation's quantity from on-hand and reserved stock", func(t *testing.T) {
		product := newTestProduct(t, 10, 6)
		reservation, _ := NewStockReservation(1, product.ID(), 100, 4, ReservationHolderCourier)

		fulfilled, err := product.Fulfill(reservation)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if fulfilled.Quantity() != 6 || fulfilled.Reserved() != 2 {
			t.Errorf("quantity = %d, reserved = %d, want 6 and 2", fulfilled.Quantity(), fulfilled.Reserved())
		}
		if fulfilled.ID() != product.ID() || fulfilled.Name() != product.Name() {
			t.Errorf("Fulfill changed unrelated fields: got %+v", fulfilled)
		}
	})

	t.Run("rejects a reservation of another product", func(t *testing.T) {
		product := newTestProduct(t, 10, 6)
		reservation, _ := NewStockReservation(1, product.ID()+1, 100, 4, ReservationHolderCourier)

		_, err := product.Fulfill(reservation)
		if !errors.Is(err, ErrForeignReservation) {
			t.Errorf("err = %v, want %v", err, ErrForeignReservation)
		}
	})

	t.Run("rejects a reservation of more than the product counts as reserved", func(t *testing.T) {
		product := newTestProduct(t, 10, 3)
		reservation, _ := NewStockReservation(1, product.ID(), 100, 4, ReservationHolderCourier)

		_, err := product.Fulfill(reservation)
		if !errors.Is(err, ErrReservedQuantityMismatch) {
			t.Errorf("err = %v, want %v", err, ErrReservedQuantityMismatch)
		}
	})

	t.Run("rejects a reservation not handed over to a courier yet", func(t *testing.T) {
		product := newTestProduct(t, 10, 6)
		reservation, _ := NewStockReservation(1, product.ID(), 100, 4, ReservationHolderBuyer)

		_, err := product.Fulfill(reservation)
		if !errors.Is(err, ErrReservationNotHeldByCourier) {
			t.Errorf("err = %v, want %v", err, ErrReservationNotHeldByCourier)
		}
	})
}

func TestProduct_Restock(t *testing.T) {
	product := newTestProduct(t, 10, 4)

	restocked := product.Restock(4)

	if restocked.Quantity() != 14 || restocked.Reserved() != 4 {
		t.Errorf("quantity = %d, reserved = %d, want 14 and 4", restocked.Quantity(), restocked.Reserved())
	}
	if restocked.ID() != product.ID() || restocked.Name() != product.Name() {
		t.Errorf("Restock changed unrelated fields: got %+v", restocked)
	}
}

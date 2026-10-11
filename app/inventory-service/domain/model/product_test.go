package model

import (
	"errors"
	"testing"

	"inventoryservice/domain"
	"inventoryservice/domain/values"

	"github.com/shopspring/decimal"
)

func testMoney(t *testing.T) values.Money {
	t.Helper()
	money, err := values.NewMoney(decimal.NewFromInt(10), values.EUR)
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

	t.Run("rejects a zero price", func(t *testing.T) {
		price, err := values.NewMoney(decimal.Zero, values.EUR)
		if err != nil {
			t.Fatalf("unexpected error building zero price: %v", err)
		}

		_, err = NewProduct(1, "Widget", "a widget", price, 10)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("rejects a price in a currency other than EUR", func(t *testing.T) {
		price, err := values.NewMoney(decimal.NewFromInt(10), values.USD)
		if err != nil {
			t.Fatalf("unexpected error building the price: %v", err)
		}

		_, err = NewProduct(1, "Widget", "a widget", price, 10)
		if !errors.Is(err, ErrUnsupportedPriceCurrency) {
			t.Errorf("err = %v, want %v", err, ErrUnsupportedPriceCurrency)
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
		description := "a gadget"
		quantity := uint(20)
		reserved := uint(4)
		product := newTestProduct(t, 10, reserved)
		version := product.Version()

		err := product.Update(name, description, testMoney(t), quantity)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if product.Name() != name || product.Description() != description || product.Quantity() != quantity {
			t.Errorf("got %+v, want name %q, description %q and quantity %d", product, name, description, quantity)
		}
		if product.Reserved() != reserved || product.Version() != version {
			t.Errorf("Update changed reserved or version: got %+v", product)
		}
	})

	t.Run("rejects a quantity below what is reserved, leaving the product unchanged", func(t *testing.T) {
		quantity := uint(10)
		product := newTestProduct(t, quantity, 4)
		name := product.Name()

		err := product.Update("Gadget", "a gadget", testMoney(t), 3)
		if !errors.Is(err, ErrQuantityBelowReserved) {
			t.Errorf("err = %v, want %v", err, ErrQuantityBelowReserved)
		}
		if product.Name() != name || product.Quantity() != quantity {
			t.Errorf("rejected update changed the product: got %+v", product)
		}
	})

	t.Run("rejects a blank name", func(t *testing.T) {
		product := newTestProduct(t, 10, 4)

		err := product.Update(" ", "a widget", testMoney(t), 10)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("rejects a zero price, leaving the product unchanged", func(t *testing.T) {
		product := newTestProduct(t, 10, 4)
		price := product.Price()
		zeroPrice, err := values.NewMoney(decimal.Zero, values.EUR)
		if err != nil {
			t.Fatalf("unexpected error building zero price: %v", err)
		}

		err = product.Update("Gadget", "a gadget", zeroPrice, 20)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
		if !product.Price().Equal(price) {
			t.Errorf("Price() = %+v, want %+v (unchanged)", product.Price(), price)
		}
	})

	t.Run("rejects a price in a currency other than EUR, leaving the product unchanged", func(t *testing.T) {
		product := newTestProduct(t, 10, 4)
		price := product.Price()
		usdPrice, err := values.NewMoney(decimal.NewFromInt(15), values.USD)
		if err != nil {
			t.Fatalf("unexpected error building the price: %v", err)
		}

		err = product.Update("Gadget", "a gadget", usdPrice, 20)
		if !errors.Is(err, ErrUnsupportedPriceCurrency) {
			t.Errorf("err = %v, want %v", err, ErrUnsupportedPriceCurrency)
		}
		if !product.Price().Equal(price) {
			t.Errorf("Price() = %+v, want %+v (unchanged)", product.Price(), price)
		}
	})
}

func TestProduct_Reserve(t *testing.T) {
	t.Run("creates a buyer-held reservation and counts it as reserved", func(t *testing.T) {
		orderID := uint64(100)
		quantity := uint(4)
		product := newTestProduct(t, 10, 2)

		reservation, err := product.Reserve(orderID, quantity)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if product.Reserved() != 6 || product.Quantity() != 10 {
			t.Errorf("reserved = %d, quantity = %d, want 6 and 10", product.Reserved(), product.Quantity())
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

		if _, err := product.Reserve(101, 6); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
	})

	t.Run("fails when requesting one more than available, leaving the product unchanged", func(t *testing.T) {
		reserved := uint(4)
		product := newTestProduct(t, 10, reserved)

		_, err := product.Reserve(101, 7)
		if !errors.Is(err, ErrInsufficientStock) {
			t.Errorf("err = %v, want %v", err, ErrInsufficientStock)
		}
		if product.Reserved() != reserved {
			t.Errorf("Reserved() = %d, want %d (unchanged)", product.Reserved(), reserved)
		}
	})

	t.Run("counts a reservation it just made against a later one", func(t *testing.T) {
		orderID := uint64(100)
		product := newTestProduct(t, 10, 0)

		if _, err := product.Reserve(orderID, 6); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		_, err := product.Reserve(orderID, 5)
		if !errors.Is(err, ErrInsufficientStock) {
			t.Errorf("err = %v, want %v", err, ErrInsufficientStock)
		}
	})
}

func TestProduct_ReleaseReservation(t *testing.T) {
	t.Run("stops counting the reservation as reserved, keeping the quantity on hand", func(t *testing.T) {
		product := newTestProduct(t, 10, 6)
		reservation, _ := NewStockReservation(1, product.ID(), 100, 4, ReservationHolderBuyer)

		err := product.ReleaseReservation(reservation)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if product.Reserved() != 2 || product.Quantity() != 10 {
			t.Errorf("reserved = %d, quantity = %d, want 2 and 10", product.Reserved(), product.Quantity())
		}
	})

	t.Run("rejects a reservation of another product, leaving the product unchanged", func(t *testing.T) {
		reserved := uint(6)
		product := newTestProduct(t, 10, reserved)
		reservation, _ := NewStockReservation(1, product.ID()+1, 100, 4, ReservationHolderBuyer)

		err := product.ReleaseReservation(reservation)
		if !errors.Is(err, ErrForeignReservation) {
			t.Errorf("err = %v, want %v", err, ErrForeignReservation)
		}
		if product.Reserved() != reserved {
			t.Errorf("Reserved() = %d, want %d (unchanged)", product.Reserved(), reserved)
		}
	})

	t.Run("rejects a reservation of more than the product counts as reserved", func(t *testing.T) {
		product := newTestProduct(t, 10, 3)
		reservation, _ := NewStockReservation(1, product.ID(), 100, 4, ReservationHolderBuyer)

		err := product.ReleaseReservation(reservation)
		if !errors.Is(err, ErrReservedQuantityMismatch) {
			t.Errorf("err = %v, want %v", err, ErrReservedQuantityMismatch)
		}
	})
}

func TestProduct_Fulfill(t *testing.T) {
	t.Run("removes the reservation's quantity from on-hand and reserved stock", func(t *testing.T) {
		product := newTestProduct(t, 10, 6)
		reservation, _ := NewStockReservation(1, product.ID(), 100, 4, ReservationHolderCourier)

		err := product.Fulfill(reservation)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if product.Quantity() != 6 || product.Reserved() != 2 {
			t.Errorf("quantity = %d, reserved = %d, want 6 and 2", product.Quantity(), product.Reserved())
		}
	})

	t.Run("rejects a reservation of another product", func(t *testing.T) {
		product := newTestProduct(t, 10, 6)
		reservation, _ := NewStockReservation(1, product.ID()+1, 100, 4, ReservationHolderCourier)

		err := product.Fulfill(reservation)
		if !errors.Is(err, ErrForeignReservation) {
			t.Errorf("err = %v, want %v", err, ErrForeignReservation)
		}
	})

	t.Run("rejects a reservation of more than the product counts as reserved", func(t *testing.T) {
		product := newTestProduct(t, 10, 3)
		reservation, _ := NewStockReservation(1, product.ID(), 100, 4, ReservationHolderCourier)

		err := product.Fulfill(reservation)
		if !errors.Is(err, ErrReservedQuantityMismatch) {
			t.Errorf("err = %v, want %v", err, ErrReservedQuantityMismatch)
		}
	})

	t.Run("rejects a reservation not handed over to a courier yet, leaving the product unchanged", func(t *testing.T) {
		quantity := uint(10)
		reserved := uint(6)
		product := newTestProduct(t, quantity, reserved)
		reservation, _ := NewStockReservation(1, product.ID(), 100, 4, ReservationHolderBuyer)

		err := product.Fulfill(reservation)
		if !errors.Is(err, ErrReservationNotHeldByCourier) {
			t.Errorf("err = %v, want %v", err, ErrReservationNotHeldByCourier)
		}
		if product.Quantity() != quantity || product.Reserved() != reserved {
			t.Errorf("quantity = %d, reserved = %d, want %d and %d (unchanged)", product.Quantity(), product.Reserved(), quantity, reserved)
		}
	})
}

func TestProduct_Restock(t *testing.T) {
	product := newTestProduct(t, 10, 4)

	product.Restock(4)

	if product.Quantity() != 14 || product.Reserved() != 4 {
		t.Errorf("quantity = %d, reserved = %d, want 14 and 4", product.Quantity(), product.Reserved())
	}
}

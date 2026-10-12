package model

import (
	"errors"
	"testing"
	"time"

	"shippingservice/domain"
	"shippingservice/domain/value"

	"github.com/google/uuid"
)

func testAddress(t *testing.T) value.Address {
	t.Helper()
	address, err := value.NewAddress("Kyiv", "01001", "Khreshchatyk 1", "")
	if err != nil {
		t.Fatalf("unexpected error building test address: %v", err)
	}
	return address
}

func newTestShipment(t *testing.T) *Shipment {
	t.Helper()
	shipment, err := NewShipment(100, testAddress(t))
	if err != nil {
		t.Fatalf("unexpected error building test shipment: %v", err)
	}
	return shipment
}

func inDeliveryShipment(t *testing.T, courierID uuid.UUID) *Shipment {
	t.Helper()
	shipment := newTestShipment(t)
	if err := shipment.StartDelivery(courierID); err != nil {
		t.Fatalf("unexpected error starting delivery: %v", err)
	}
	return shipment
}

func cancelledAfterPickupShipment(t *testing.T, courierID uuid.UUID) *Shipment {
	t.Helper()
	shipment := inDeliveryShipment(t, courierID)
	if err := shipment.Cancel(RecipientRefused); err != nil {
		t.Fatalf("unexpected error cancelling: %v", err)
	}
	return shipment
}

// assertRejectedUnchanged checks a rejected transition left the shipment exactly as it was.
func assertRejectedUnchanged(t *testing.T, shipment *Shipment, err error, wantStatus ShipmentStatus, wantHolder ShipmentHolder) {
	t.Helper()
	if !errors.Is(err, ErrInvalidStatusTransition) {
		t.Errorf("err = %v, want %v", err, ErrInvalidStatusTransition)
	}
	if shipment.Status() != wantStatus || shipment.Holder() != wantHolder {
		t.Errorf("rejected transition changed the shipment: status %v, holder %v", shipment.Status(), shipment.Holder())
	}
}

func TestNewShipment(t *testing.T) {
	t.Run("creates a shipment in Created status, held by inventory, with no courier", func(t *testing.T) {
		orderID := uint64(100)

		shipment, err := NewShipment(orderID, testAddress(t))

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if shipment.OrderId() != orderID {
			t.Errorf("OrderId() = %d, want %d", shipment.OrderId(), orderID)
		}
		if shipment.Status() != Created || shipment.Holder() != HolderInventory || shipment.CourierID() != uuid.Nil {
			t.Errorf("state = %v/%v/%v, want %v/%v/none", shipment.Status(), shipment.Holder(), shipment.CourierID(), Created, HolderInventory)
		}
		if !shipment.UpdatedAt().IsZero() {
			t.Errorf("UpdatedAt() = %v, want zero", shipment.UpdatedAt())
		}
	})

	t.Run("rejects a zero orderId", func(t *testing.T) {
		_, err := NewShipment(0, testAddress(t))

		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})
}

func TestNewExistingShipment(t *testing.T) {
	now := time.Now()

	t.Run("creates a shipment with the given fields", func(t *testing.T) {
		id := uint64(1)
		orderID := uint64(100)
		version := uint64(7)
		courierID := uuid.New()

		shipment, err := NewExistingShipment(id, orderID, courierID, testAddress(t), InDelivery, HolderCourier, NoCancellationReason, now, now, version)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if shipment.Id() != id || shipment.OrderId() != orderID || shipment.Version() != version {
			t.Errorf("id/orderId/version = %d/%d/%d, want %d/%d/%d", shipment.Id(), shipment.OrderId(), shipment.Version(), id, orderID, version)
		}
		if shipment.Status() != InDelivery || shipment.Holder() != HolderCourier || shipment.CourierID() != courierID {
			t.Errorf("state = %v/%v/%v, want %v/%v/%v", shipment.Status(), shipment.Holder(), shipment.CourierID(), InDelivery, HolderCourier, courierID)
		}
	})

	t.Run("creates a cancelled shipment with its reason", func(t *testing.T) {
		reason := RecipientUnavailable

		shipment, err := NewExistingShipment(1, 100, uuid.New(), testAddress(t), Cancelled, HolderCourier, reason, now, now, 0)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if shipment.CancellationReason() != reason {
			t.Errorf("CancellationReason() = %q, want %q", shipment.CancellationReason(), reason)
		}
	})

	t.Run("rejects a zero id", func(t *testing.T) {
		_, err := NewExistingShipment(0, 100, uuid.Nil, testAddress(t), Created, HolderInventory, NoCancellationReason, now, now, 0)

		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("rejects a zero orderId", func(t *testing.T) {
		_, err := NewExistingShipment(1, 0, uuid.Nil, testAddress(t), Created, HolderInventory, NoCancellationReason, now, now, 0)

		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("rejects a state a shipment can't be in", func(t *testing.T) {
		_, err := NewExistingShipment(1, 100, uuid.Nil, testAddress(t), InDelivery, HolderCourier, NoCancellationReason, now, now, 0)

		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})
}

func TestShipment_StartDelivery(t *testing.T) {
	t.Run("moves to InDelivery and assigns the shipment to the courier", func(t *testing.T) {
		courierID := uuid.New()
		shipment := newTestShipment(t)

		err := shipment.StartDelivery(courierID)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if shipment.Status() != InDelivery || shipment.Holder() != HolderCourier || shipment.CourierID() != courierID {
			t.Errorf("state = %v/%v/%v, want %v/%v/%v", shipment.Status(), shipment.Holder(), shipment.CourierID(), InDelivery, HolderCourier, courierID)
		}
		if shipment.UpdatedAt().IsZero() {
			t.Error("UpdatedAt() is zero, want the time of the pickup")
		}
	})

	t.Run("rejects a shipment that is not Created, leaving it unchanged", func(t *testing.T) {
		shipment := inDeliveryShipment(t, uuid.New())
		updatedAt := shipment.UpdatedAt()

		err := shipment.StartDelivery(uuid.New())

		assertRejectedUnchanged(t, shipment, err, InDelivery, HolderCourier)
		if !shipment.UpdatedAt().Equal(updatedAt) {
			t.Errorf("UpdatedAt() = %v, want unchanged %v", shipment.UpdatedAt(), updatedAt)
		}
	})
}

func TestShipment_Deliver(t *testing.T) {
	t.Run("moves to Delivered", func(t *testing.T) {
		shipment := inDeliveryShipment(t, uuid.New())

		if err := shipment.Deliver(); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if shipment.Status() != Delivered {
			t.Errorf("Status() = %v, want %v", shipment.Status(), Delivered)
		}
	})

	t.Run("rejects a shipment that is not InDelivery, leaving it unchanged", func(t *testing.T) {
		shipment := newTestShipment(t)

		err := shipment.Deliver()

		assertRejectedUnchanged(t, shipment, err, Created, HolderInventory)
	})
}

func TestShipment_Lose(t *testing.T) {
	t.Run("moves to Lost", func(t *testing.T) {
		shipment := inDeliveryShipment(t, uuid.New())

		if err := shipment.Lose(); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if shipment.Status() != Lost {
			t.Errorf("Status() = %v, want %v", shipment.Status(), Lost)
		}
	})

	t.Run("rejects a shipment that is not InDelivery, leaving it unchanged", func(t *testing.T) {
		shipment := newTestShipment(t)

		err := shipment.Lose()

		assertRejectedUnchanged(t, shipment, err, Created, HolderInventory)
	})
}

func TestShipment_Cancel(t *testing.T) {
	t.Run("moves to Cancelled for the given reason", func(t *testing.T) {
		reason := OrderCancelled
		shipment := newTestShipment(t)

		if err := shipment.Cancel(reason); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if shipment.Status() != Cancelled {
			t.Errorf("Status() = %v, want %v", shipment.Status(), Cancelled)
		}
		if shipment.CancellationReason() != reason {
			t.Errorf("CancellationReason() = %q, want %q", shipment.CancellationReason(), reason)
		}
		if shipment.UpdatedAt().IsZero() {
			t.Error("UpdatedAt() is zero, want the time of the cancellation")
		}
	})

	t.Run("rejects a shipment that already reached a terminal status, leaving it unchanged", func(t *testing.T) {
		shipment := cancelledAfterPickupShipment(t, uuid.New())

		err := shipment.Cancel(OrderCancelled)

		assertRejectedUnchanged(t, shipment, err, Cancelled, HolderCourier)
		if shipment.CancellationReason() != RecipientRefused {
			t.Errorf("CancellationReason() = %q, want %q", shipment.CancellationReason(), RecipientRefused)
		}
	})
}

func TestShipment_CanBeCancelled(t *testing.T) {
	t.Run("true before pickup and while in delivery", func(t *testing.T) {
		if !newTestShipment(t).CanBeCancelled() {
			t.Error("CanBeCancelled() = false before pickup, want true")
		}
		if !inDeliveryShipment(t, uuid.New()).CanBeCancelled() {
			t.Error("CanBeCancelled() = false in delivery, want true")
		}
	})

	t.Run("false once the shipment has ended", func(t *testing.T) {
		delivered := inDeliveryShipment(t, uuid.New())
		if err := delivered.Deliver(); err != nil {
			t.Fatalf("unexpected error delivering: %v", err)
		}

		if delivered.CanBeCancelled() {
			t.Error("CanBeCancelled() = true for a delivered shipment, want false")
		}
		if cancelledAfterPickupShipment(t, uuid.New()).CanBeCancelled() {
			t.Error("CanBeCancelled() = true for a cancelled shipment, want false")
		}
	})
}

func TestShipment_ConfirmReturnedToInventory(t *testing.T) {
	t.Run("moves the holder back to inventory, keeping the status cancelled", func(t *testing.T) {
		shipment := cancelledAfterPickupShipment(t, uuid.New())

		if err := shipment.ConfirmReturnedToInventory(); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if shipment.Status() != Cancelled || shipment.Holder() != HolderInventory {
			t.Errorf("status/holder = %v/%v, want %v/%v", shipment.Status(), shipment.Holder(), Cancelled, HolderInventory)
		}
	})

	t.Run("rejects a shipment that is not cancelled, leaving it unchanged", func(t *testing.T) {
		shipment := inDeliveryShipment(t, uuid.New())

		err := shipment.ConfirmReturnedToInventory()

		assertRejectedUnchanged(t, shipment, err, InDelivery, HolderCourier)
	})
}

func TestShipment_IsAssignedTo(t *testing.T) {
	t.Run("true for the courier who picked the shipment up", func(t *testing.T) {
		courierID := uuid.New()

		if !inDeliveryShipment(t, courierID).IsAssignedTo(courierID) {
			t.Error("IsAssignedTo() = false, want true")
		}
	})

	t.Run("false for another courier", func(t *testing.T) {
		if inDeliveryShipment(t, uuid.New()).IsAssignedTo(uuid.New()) {
			t.Error("IsAssignedTo() = true, want false")
		}
	})

	t.Run("false for any courier before pickup", func(t *testing.T) {
		if newTestShipment(t).IsAssignedTo(uuid.Nil) {
			t.Error("IsAssignedTo(uuid.Nil) = true, want false")
		}
	})
}

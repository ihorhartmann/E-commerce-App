package model

import (
	"errors"
	"testing"
	"time"

	"shippingservice/domain"
	"shippingservice/domain/value"
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

func inDeliveryShipment(t *testing.T) *Shipment {
	t.Helper()
	shipment := newTestShipment(t)
	err := shipment.StartDelivery()
	if err != nil {
		t.Fatalf("unexpected error starting delivery: %v", err)
	}
	return shipment
}

func cancelledAfterPickupShipment(t *testing.T) *Shipment {
	t.Helper()
	shipment := inDeliveryShipment(t)
	err := shipment.Cancel()
	if err != nil {
		t.Fatalf("unexpected error cancelling: %v", err)
	}
	return shipment
}

func cancelledBeforePickupShipment(t *testing.T) *Shipment {
	t.Helper()
	shipment := newTestShipment(t)
	err := shipment.Cancel()
	if err != nil {
		t.Fatalf("unexpected error cancelling: %v", err)
	}
	return shipment
}

func TestNewShipment(t *testing.T) {
	t.Run("creates a shipment in Created status, held by inventory, with a generated tracking number", func(t *testing.T) {
		orderID := uint64(100)
		shipment, err := NewShipment(orderID, testAddress(t))
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if shipment.OrderId() != orderID {
			t.Errorf("OrderId() = %d, want %d", shipment.OrderId(), orderID)
		}
		if shipment.TrackingNumber() == "" {
			t.Error("TrackingNumber() is empty, want a generated value")
		}
		if shipment.Status() != Created {
			t.Errorf("Status() = %v, want %v", shipment.Status(), Created)
		}
		if shipment.Holder() != HolderInventory {
			t.Errorf("Holder() = %v, want %v", shipment.Holder(), HolderInventory)
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
		version := uint64(7)
		shipment, err := NewExistingShipment(id, 100, "TRACK-1", testAddress(t), InDelivery, HolderCourier, now, now, version)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if shipment.Id() != id {
			t.Errorf("Id() = %d, want %d", shipment.Id(), id)
		}
		if shipment.Version() != version {
			t.Errorf("Version() = %d, want %d", shipment.Version(), version)
		}
		if shipment.Status() != InDelivery {
			t.Errorf("Status() = %v, want %v", shipment.Status(), InDelivery)
		}
		if shipment.Holder() != HolderCourier {
			t.Errorf("Holder() = %v, want %v", shipment.Holder(), HolderCourier)
		}
	})

	t.Run("rejects a zero id", func(t *testing.T) {
		_, err := NewExistingShipment(0, 100, "TRACK-1", testAddress(t), Created, HolderInventory, now, now, 0)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("rejects an invalid status", func(t *testing.T) {
		_, err := NewExistingShipment(1, 100, "TRACK-1", testAddress(t), "Floating", HolderInventory, now, now, 0)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("rejects an invalid holder", func(t *testing.T) {
		_, err := NewExistingShipment(1, 100, "TRACK-1", testAddress(t), Created, "Somewhere", now, now, 0)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("rejects a holder that doesn't match the status", func(t *testing.T) {
		testCases := []struct {
			status ShipmentStatus
			holder ShipmentHolder
		}{
			{Created, HolderCourier},
			{InDelivery, HolderInventory},
			{Delivered, HolderInventory},
			{Lost, HolderInventory},
		}
		for _, tt := range testCases {
			_, err := NewExistingShipment(1, 100, "TRACK-1", testAddress(t), tt.status, tt.holder, now, now, 0)
			if !errors.Is(err, domain.ErrInvalidInput) {
				t.Errorf("status %s, holder %s: err = %v, want %v", tt.status, tt.holder, err, domain.ErrInvalidInput)
			}
		}
	})

	t.Run("accepts a cancelled shipment held by either the inventory or the courier", func(t *testing.T) {
		for _, holder := range []ShipmentHolder{HolderInventory, HolderCourier} {
			shipment, err := NewExistingShipment(1, 100, "TRACK-1", testAddress(t), Cancelled, holder, now, now, 0)
			if err != nil {
				t.Fatalf("holder %s: unexpected error: %v", holder, err)
			}
			if shipment.Holder() != holder {
				t.Errorf("Holder() = %v, want %v", shipment.Holder(), holder)
			}
		}
	})

	t.Run("rejects a zero orderId", func(t *testing.T) {
		_, err := NewExistingShipment(1, 0, "TRACK-1", testAddress(t), Created, HolderInventory, now, now, 0)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("rejects a blank tracking number", func(t *testing.T) {
		_, err := NewExistingShipment(1, 100, "  ", testAddress(t), Created, HolderInventory, now, now, 0)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})
}

func TestShipment_StartDelivery(t *testing.T) {
	t.Run("moves to InDelivery and hands the goods to the courier", func(t *testing.T) {
		shipment := newTestShipment(t)
		err := shipment.StartDelivery()
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if shipment.Status() != InDelivery {
			t.Errorf("Status() = %v, want %v", shipment.Status(), InDelivery)
		}
		if shipment.Holder() != HolderCourier {
			t.Errorf("Holder() = %v, want %v", shipment.Holder(), HolderCourier)
		}
	})

	t.Run("throws when shipment is not Created, leaving it unchanged", func(t *testing.T) {
		shipment := cancelledBeforePickupShipment(t)
		updatedAt := shipment.UpdatedAt()

		err := shipment.StartDelivery()
		if !errors.Is(err, ErrInvalidStatusTransition) {
			t.Errorf("err = %v, want %v", err, ErrInvalidStatusTransition)
		}
		if shipment.Status() != Cancelled || shipment.Holder() != HolderInventory || !shipment.UpdatedAt().Equal(updatedAt) {
			t.Errorf("rejected transition changed the shipment: status %v, holder %v", shipment.Status(), shipment.Holder())
		}
	})
}

func TestShipment_Deliver(t *testing.T) {
	t.Run("moves to Delivered, keeping the courier as holder", func(t *testing.T) {
		shipment := inDeliveryShipment(t)
		err := shipment.Deliver()
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if shipment.Status() != Delivered {
			t.Errorf("Status() = %v, want %v", shipment.Status(), Delivered)
		}
		if shipment.Holder() != HolderCourier {
			t.Errorf("Holder() = %v, want %v", shipment.Holder(), HolderCourier)
		}
	})

	t.Run("throws when shipment is not InDelivery", func(t *testing.T) {
		err := newTestShipment(t).Deliver()
		if !errors.Is(err, ErrInvalidStatusTransition) {
			t.Errorf("err = %v, want %v", err, ErrInvalidStatusTransition)
		}
	})
}

func TestShipment_Lose(t *testing.T) {
	t.Run("moves to Lost, keeping the courier as holder", func(t *testing.T) {
		shipment := inDeliveryShipment(t)
		err := shipment.Lose()
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if shipment.Status() != Lost {
			t.Errorf("Status() = %v, want %v", shipment.Status(), Lost)
		}
		if shipment.Holder() != HolderCourier {
			t.Errorf("Holder() = %v, want %v", shipment.Holder(), HolderCourier)
		}
	})

	t.Run("throws when shipment is not InDelivery", func(t *testing.T) {
		err := newTestShipment(t).Lose()
		if !errors.Is(err, ErrInvalidStatusTransition) {
			t.Errorf("err = %v, want %v", err, ErrInvalidStatusTransition)
		}
	})
}

func TestShipment_Cancel(t *testing.T) {
	t.Run("cancels before pickup, keeping the goods with inventory", func(t *testing.T) {
		shipment := newTestShipment(t)
		err := shipment.Cancel()
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if shipment.Status() != Cancelled {
			t.Errorf("Status() = %v, want %v", shipment.Status(), Cancelled)
		}
		if shipment.Holder() != HolderInventory {
			t.Errorf("Holder() = %v, want %v", shipment.Holder(), HolderInventory)
		}
	})

	t.Run("cancels after pickup, keeping the goods with the courier", func(t *testing.T) {
		shipment := inDeliveryShipment(t)
		err := shipment.Cancel()
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if shipment.Status() != Cancelled {
			t.Errorf("Status() = %v, want %v", shipment.Status(), Cancelled)
		}
		if shipment.Holder() != HolderCourier {
			t.Errorf("Holder() = %v, want %v", shipment.Holder(), HolderCourier)
		}
	})

	t.Run("throws when shipment already reached a terminal status", func(t *testing.T) {
		delivered := inDeliveryShipment(t)
		err := delivered.Deliver()
		if err != nil {
			t.Fatalf("unexpected error setting up delivered shipment: %v", err)
		}

		err = delivered.Cancel()
		if !errors.Is(err, ErrInvalidStatusTransition) {
			t.Errorf("err = %v, want %v", err, ErrInvalidStatusTransition)
		}
	})
}

func TestShipment_ConfirmReturnedToInventory(t *testing.T) {
	t.Run("moves the holder back to inventory, keeping the status cancelled", func(t *testing.T) {
		shipment := cancelledAfterPickupShipment(t)
		err := shipment.ConfirmReturnedToInventory()
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if shipment.Status() != Cancelled {
			t.Errorf("Status() = %v, want %v", shipment.Status(), Cancelled)
		}
		if shipment.Holder() != HolderInventory {
			t.Errorf("Holder() = %v, want %v", shipment.Holder(), HolderInventory)
		}
	})

	t.Run("throws when the goods were never picked up by a courier", func(t *testing.T) {
		err := cancelledBeforePickupShipment(t).ConfirmReturnedToInventory()
		if !errors.Is(err, ErrInvalidStatusTransition) {
			t.Errorf("err = %v, want %v", err, ErrInvalidStatusTransition)
		}
	})

	t.Run("throws when shipment is not cancelled", func(t *testing.T) {
		err := inDeliveryShipment(t).ConfirmReturnedToInventory()
		if !errors.Is(err, ErrInvalidStatusTransition) {
			t.Errorf("err = %v, want %v", err, ErrInvalidStatusTransition)
		}
	})
}

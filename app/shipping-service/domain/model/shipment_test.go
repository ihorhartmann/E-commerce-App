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
	shipment, err := NewShipment(100, uuid.New(), testAddress(t))
	if err != nil {
		t.Fatalf("unexpected error building test shipment: %v", err)
	}
	return shipment
}

func inDeliveryShipment(t *testing.T, courierID uuid.UUID) *Shipment {
	t.Helper()
	shipment := newTestShipment(t)
	err := shipment.StartDelivery(courierID)
	if err != nil {
		t.Fatalf("unexpected error starting delivery: %v", err)
	}
	return shipment
}

func cancelledAfterPickupShipment(t *testing.T, courierID uuid.UUID) *Shipment {
	t.Helper()
	shipment := inDeliveryShipment(t, courierID)
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
		customerID := uuid.New()
		shipment, err := NewShipment(orderID, customerID, testAddress(t))
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if shipment.OrderId() != orderID {
			t.Errorf("OrderId() = %d, want %d", shipment.OrderId(), orderID)
		}
		if shipment.CustomerID() != customerID {
			t.Errorf("CustomerID() = %v, want %v", shipment.CustomerID(), customerID)
		}
		if shipment.CourierID() != uuid.Nil {
			t.Errorf("CourierID() = %v, want none before pickup", shipment.CourierID())
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
		_, err := NewShipment(0, uuid.New(), testAddress(t))
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("rejects a missing customer", func(t *testing.T) {
		_, err := NewShipment(100, uuid.Nil, testAddress(t))
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
		customerID := uuid.New()
		courierID := uuid.New()
		shipment, err := NewExistingShipment(id, 100, customerID, courierID, "TRACK-1", testAddress(t), InDelivery, HolderCourier, now, now, version)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if shipment.Id() != id {
			t.Errorf("Id() = %d, want %d", shipment.Id(), id)
		}
		if shipment.CustomerID() != customerID {
			t.Errorf("CustomerID() = %v, want %v", shipment.CustomerID(), customerID)
		}
		if shipment.CourierID() != courierID {
			t.Errorf("CourierID() = %v, want %v", shipment.CourierID(), courierID)
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
		_, err := NewExistingShipment(0, 100, uuid.New(), uuid.Nil, "TRACK-1", testAddress(t), Created, HolderInventory, now, now, 0)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("rejects an invalid status", func(t *testing.T) {
		_, err := NewExistingShipment(1, 100, uuid.New(), uuid.Nil, "TRACK-1", testAddress(t), "Floating", HolderInventory, now, now, 0)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("rejects an invalid holder", func(t *testing.T) {
		_, err := NewExistingShipment(1, 100, uuid.New(), uuid.Nil, "TRACK-1", testAddress(t), Created, "Somewhere", now, now, 0)
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
			_, err := NewExistingShipment(1, 100, uuid.New(), uuid.New(), "TRACK-1", testAddress(t), tt.status, tt.holder, now, now, 0)
			if !errors.Is(err, domain.ErrInvalidInput) {
				t.Errorf("status %s, holder %s: err = %v, want %v", tt.status, tt.holder, err, domain.ErrInvalidInput)
			}
		}
	})

	t.Run("rejects a courier that doesn't match the status", func(t *testing.T) {
		testCases := []struct {
			name      string
			status    ShipmentStatus
			holder    ShipmentHolder
			courierID uuid.UUID
		}{
			{"a courier before pickup", Created, HolderInventory, uuid.New()},
			{"no courier while in delivery", InDelivery, HolderCourier, uuid.Nil},
			{"no courier once delivered", Delivered, HolderCourier, uuid.Nil},
			{"no courier once lost", Lost, HolderCourier, uuid.Nil},
			{"no courier for a cancelled shipment still on its way back", Cancelled, HolderCourier, uuid.Nil},
		}
		for _, testCase := range testCases {
			_, err := NewExistingShipment(1, 100, uuid.New(), testCase.courierID, "TRACK-1", testAddress(t), testCase.status, testCase.holder, now, now, 0)
			if !errors.Is(err, domain.ErrInvalidInput) {
				t.Errorf("%s: err = %v, want %v", testCase.name, err, domain.ErrInvalidInput)
			}
		}
	})

	t.Run("accepts a cancelled shipment held by either the inventory or the courier", func(t *testing.T) {
		for _, holder := range []ShipmentHolder{HolderInventory, HolderCourier} {
			shipment, err := NewExistingShipment(1, 100, uuid.New(), uuid.New(), "TRACK-1", testAddress(t), Cancelled, holder, now, now, 0)
			if err != nil {
				t.Fatalf("holder %s: unexpected error: %v", holder, err)
			}
			if shipment.Holder() != holder {
				t.Errorf("Holder() = %v, want %v", shipment.Holder(), holder)
			}
		}
	})

	t.Run("accepts a shipment cancelled back at the inventory with or without a courier", func(t *testing.T) {
		for _, courierID := range []uuid.UUID{uuid.Nil, uuid.New()} {
			_, err := NewExistingShipment(1, 100, uuid.New(), courierID, "TRACK-1", testAddress(t), Cancelled, HolderInventory, now, now, 0)
			if err != nil {
				t.Errorf("courier %v: unexpected error: %v", courierID, err)
			}
		}
	})

	t.Run("rejects a zero orderId", func(t *testing.T) {
		_, err := NewExistingShipment(1, 0, uuid.New(), uuid.Nil, "TRACK-1", testAddress(t), Created, HolderInventory, now, now, 0)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("rejects a missing customer", func(t *testing.T) {
		_, err := NewExistingShipment(1, 100, uuid.Nil, uuid.Nil, "TRACK-1", testAddress(t), Created, HolderInventory, now, now, 0)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})

	t.Run("rejects a blank tracking number", func(t *testing.T) {
		_, err := NewExistingShipment(1, 100, uuid.New(), uuid.Nil, "  ", testAddress(t), Created, HolderInventory, now, now, 0)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})
}

func TestShipment_StartDelivery(t *testing.T) {
	t.Run("moves to InDelivery, hands the goods to the courier and assigns the shipment to them", func(t *testing.T) {
		courierID := uuid.New()
		shipment := newTestShipment(t)
		err := shipment.StartDelivery(courierID)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if shipment.Status() != InDelivery {
			t.Errorf("Status() = %v, want %v", shipment.Status(), InDelivery)
		}
		if shipment.Holder() != HolderCourier {
			t.Errorf("Holder() = %v, want %v", shipment.Holder(), HolderCourier)
		}
		if shipment.CourierID() != courierID {
			t.Errorf("CourierID() = %v, want %v", shipment.CourierID(), courierID)
		}
	})

	t.Run("throws when no courier is given, leaving the shipment unchanged", func(t *testing.T) {
		shipment := newTestShipment(t)

		err := shipment.StartDelivery(uuid.Nil)
		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
		if shipment.Status() != Created || shipment.Holder() != HolderInventory || shipment.CourierID() != uuid.Nil {
			t.Errorf("rejected transition changed the shipment: status %v, holder %v", shipment.Status(), shipment.Holder())
		}
	})

	t.Run("throws when shipment is not Created, leaving it unchanged", func(t *testing.T) {
		shipment := cancelledBeforePickupShipment(t)
		updatedAt := shipment.UpdatedAt()

		err := shipment.StartDelivery(uuid.New())
		if !errors.Is(err, ErrInvalidStatusTransition) {
			t.Errorf("err = %v, want %v", err, ErrInvalidStatusTransition)
		}
		if shipment.Status() != Cancelled || shipment.Holder() != HolderInventory || shipment.CourierID() != uuid.Nil ||
			!shipment.UpdatedAt().Equal(updatedAt) {
			t.Errorf("rejected transition changed the shipment: status %v, holder %v", shipment.Status(), shipment.Holder())
		}
	})
}

func TestShipment_Deliver(t *testing.T) {
	t.Run("moves to Delivered, keeping the courier as holder", func(t *testing.T) {
		shipment := inDeliveryShipment(t, uuid.New())
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
		shipment := inDeliveryShipment(t, uuid.New())
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
		shipment := inDeliveryShipment(t, uuid.New())
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
		delivered := inDeliveryShipment(t, uuid.New())
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
		shipment := cancelledAfterPickupShipment(t, uuid.New())
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
		err := inDeliveryShipment(t, uuid.New()).ConfirmReturnedToInventory()
		if !errors.Is(err, ErrInvalidStatusTransition) {
			t.Errorf("err = %v, want %v", err, ErrInvalidStatusTransition)
		}
	})

}

func TestShipment_IsOwnedBy(t *testing.T) {
	t.Run("true for the customer who placed the order", func(t *testing.T) {
		customerID := uuid.New()
		shipment, err := NewShipment(100, customerID, testAddress(t))
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if !shipment.IsOwnedBy(customerID) {
			t.Error("IsOwnedBy() = false, want true")
		}
	})

	t.Run("false for another customer", func(t *testing.T) {
		if newTestShipment(t).IsOwnedBy(uuid.New()) {
			t.Error("IsOwnedBy() = true, want false")
		}
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

package model

import (
	"errors"
	"testing"

	"shippingservice/domain"

	"github.com/google/uuid"
)

func mustShipmentState(t *testing.T, status ShipmentStatus, holder ShipmentHolder, courierID uuid.UUID) shipmentState {
	t.Helper()
	state, err := newShipmentState(status, holder, courierID)
	if err != nil {
		t.Fatalf("unexpected error building test state: %v", err)
	}
	return state
}

func assertState(t *testing.T, got shipmentState, wantStatus ShipmentStatus, wantHolder ShipmentHolder, wantCourierID uuid.UUID) {
	t.Helper()
	if got.status != wantStatus || got.holder != wantHolder || got.courierID != wantCourierID {
		t.Errorf("state = %+v, want status %s, holder %s, courier %s", got, wantStatus, wantHolder, wantCourierID)
	}
}

func TestNewShipmentState(t *testing.T) {
	courierID := uuid.New()

	t.Run("accepts every state a shipment can be in", func(t *testing.T) {
		testCases := []struct {
			name      string
			status    ShipmentStatus
			holder    ShipmentHolder
			courierID uuid.UUID
		}{
			{"created, at the inventory", Created, HolderInventory, uuid.Nil},
			{"in delivery with its courier", InDelivery, HolderCourier, courierID},
			{"delivered by its courier", Delivered, HolderCourier, courierID},
			{"lost by its courier", Lost, HolderCourier, courierID},
			{"cancelled before pickup", Cancelled, HolderInventory, uuid.Nil},
			{"cancelled, still with the courier", Cancelled, HolderCourier, courierID},
			{"cancelled and returned to the inventory", Cancelled, HolderInventory, courierID},
		}
		for _, testCase := range testCases {
			state, err := newShipmentState(testCase.status, testCase.holder, testCase.courierID)
			if err != nil {
				t.Errorf("%s: unexpected error: %v", testCase.name, err)
				continue
			}
			assertState(t, state, testCase.status, testCase.holder, testCase.courierID)
		}
	})

	t.Run("rejects a state a shipment can't be in", func(t *testing.T) {
		testCases := []struct {
			name      string
			status    ShipmentStatus
			holder    ShipmentHolder
			courierID uuid.UUID
		}{
			{"an unknown status", "Floating", HolderInventory, uuid.Nil},
			{"an unknown holder", Created, "Somewhere", uuid.Nil},
			{"created, held by a courier", Created, HolderCourier, uuid.Nil},
			{"created, with a courier", Created, HolderInventory, courierID},
			{"in delivery at the inventory", InDelivery, HolderInventory, courierID},
			{"in delivery without a courier", InDelivery, HolderCourier, uuid.Nil},
			{"delivered at the inventory", Delivered, HolderInventory, courierID},
			{"delivered without a courier", Delivered, HolderCourier, uuid.Nil},
			{"lost at the inventory", Lost, HolderInventory, courierID},
			{"lost without a courier", Lost, HolderCourier, uuid.Nil},
			{"cancelled, held by a courier that isn't there", Cancelled, HolderCourier, uuid.Nil},
		}
		for _, testCase := range testCases {
			_, err := newShipmentState(testCase.status, testCase.holder, testCase.courierID)
			if !errors.Is(err, domain.ErrInvalidInput) {
				t.Errorf("%s: err = %v, want %v", testCase.name, err, domain.ErrInvalidInput)
			}
		}
	})
}

// assertRejected checks a transition was rejected with wantErr and left the state exactly as it was.
func assertRejected(t *testing.T, err error, wantErr error, got shipmentState, want shipmentState) {
	t.Helper()
	if !errors.Is(err, wantErr) {
		t.Errorf("err = %v, want %v", err, wantErr)
	}
	if got != want {
		t.Errorf("rejected transition changed the state to %+v, want %+v", got, want)
	}
}

func TestShipmentState_StartDelivery(t *testing.T) {
	t.Run("hands the goods to the courier and assigns the shipment to them", func(t *testing.T) {
		courierID := uuid.New()
		state := mustShipmentState(t, Created, HolderInventory, uuid.Nil)

		err := state.startDelivery(courierID)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		assertState(t, state, InDelivery, HolderCourier, courierID)
	})

	t.Run("rejects a missing courier, leaving the state unchanged", func(t *testing.T) {
		state := mustShipmentState(t, Created, HolderInventory, uuid.Nil)
		before := state

		err := state.startDelivery(uuid.Nil)

		assertRejected(t, err, domain.ErrInvalidInput, state, before)
	})

	t.Run("rejects a shipment that is not Created, leaving the state unchanged", func(t *testing.T) {
		state := mustShipmentState(t, InDelivery, HolderCourier, uuid.New())
		before := state

		err := state.startDelivery(uuid.New())

		assertRejected(t, err, ErrInvalidStatusTransition, state, before)
	})
}

func TestShipmentState_Deliver(t *testing.T) {
	t.Run("moves to Delivered, keeping the courier", func(t *testing.T) {
		courierID := uuid.New()
		state := mustShipmentState(t, InDelivery, HolderCourier, courierID)

		err := state.deliver()

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		assertState(t, state, Delivered, HolderCourier, courierID)
	})

	t.Run("rejects a shipment that is not InDelivery, leaving the state unchanged", func(t *testing.T) {
		state := mustShipmentState(t, Created, HolderInventory, uuid.Nil)
		before := state

		err := state.deliver()

		assertRejected(t, err, ErrInvalidStatusTransition, state, before)
	})
}

func TestShipmentState_Lose(t *testing.T) {
	t.Run("moves to Lost, keeping the courier", func(t *testing.T) {
		courierID := uuid.New()
		state := mustShipmentState(t, InDelivery, HolderCourier, courierID)

		err := state.lose()

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		assertState(t, state, Lost, HolderCourier, courierID)
	})

	t.Run("rejects a shipment that is not InDelivery, leaving the state unchanged", func(t *testing.T) {
		state := mustShipmentState(t, Created, HolderInventory, uuid.Nil)
		before := state

		err := state.lose()

		assertRejected(t, err, ErrInvalidStatusTransition, state, before)
	})
}

func TestShipmentState_Cancel(t *testing.T) {
	t.Run("before pickup keeps the goods at the inventory", func(t *testing.T) {
		state := mustShipmentState(t, Created, HolderInventory, uuid.Nil)

		err := state.cancel()

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		assertState(t, state, Cancelled, HolderInventory, uuid.Nil)
	})

	t.Run("after pickup keeps the goods with the courier", func(t *testing.T) {
		courierID := uuid.New()
		state := mustShipmentState(t, InDelivery, HolderCourier, courierID)

		err := state.cancel()

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		assertState(t, state, Cancelled, HolderCourier, courierID)
	})

	t.Run("rejects a shipment that already reached a terminal status, leaving the state unchanged", func(t *testing.T) {
		state := mustShipmentState(t, Delivered, HolderCourier, uuid.New())
		before := state

		err := state.cancel()

		assertRejected(t, err, ErrInvalidStatusTransition, state, before)
	})
}

func TestShipmentState_ReturnToInventory(t *testing.T) {
	t.Run("moves the goods back to the inventory, keeping the status and the courier", func(t *testing.T) {
		courierID := uuid.New()
		state := mustShipmentState(t, Cancelled, HolderCourier, courierID)

		err := state.returnToInventory()

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		assertState(t, state, Cancelled, HolderInventory, courierID)
	})

	t.Run("rejects a cancelled shipment whose goods are already at the inventory, leaving the state unchanged", func(t *testing.T) {
		state := mustShipmentState(t, Cancelled, HolderInventory, uuid.Nil)
		before := state

		err := state.returnToInventory()

		assertRejected(t, err, ErrInvalidStatusTransition, state, before)
	})

	t.Run("rejects a shipment that is not cancelled, leaving the state unchanged", func(t *testing.T) {
		state := mustShipmentState(t, InDelivery, HolderCourier, uuid.New())
		before := state

		err := state.returnToInventory()

		assertRejected(t, err, ErrInvalidStatusTransition, state, before)
	})
}

func TestShipmentState_Change(t *testing.T) {
	t.Run("moves to a state a shipment can be in", func(t *testing.T) {
		courierID := uuid.New()
		state := mustShipmentState(t, Created, HolderInventory, uuid.Nil)

		err := state.change(InDelivery, HolderCourier, courierID)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		assertState(t, state, InDelivery, HolderCourier, courierID)
	})

	t.Run("rejects a state a shipment can't be in, leaving the state unchanged", func(t *testing.T) {
		state := mustShipmentState(t, Created, HolderInventory, uuid.Nil)
		before := state

		err := state.change(InDelivery, HolderInventory, uuid.Nil)

		assertRejected(t, err, domain.ErrInvalidInput, state, before)
	})
}

package model

import (
	"errors"
	"testing"

	"github.com/google/uuid"
)

// mustShipmentState builds a state that isn't cancelled; mustCancelledState builds a cancelled one.
func mustShipmentState(t *testing.T, status ShipmentStatus, holder ShipmentHolder, courierID uuid.UUID) shipmentState {
	t.Helper()
	state, err := newShipmentState(status, holder, courierID, NoCancellationReason)
	if err != nil {
		t.Fatalf("unexpected error building test state: %v", err)
	}
	return state
}

func mustCancelledState(t *testing.T, holder ShipmentHolder, courierID uuid.UUID, reason CancellationReason) shipmentState {
	t.Helper()
	state, err := newShipmentState(Cancelled, holder, courierID, reason)
	if err != nil {
		t.Fatalf("unexpected error building test state: %v", err)
	}
	return state
}

func assertState(t *testing.T, got shipmentState, wantStatus ShipmentStatus, wantHolder ShipmentHolder, wantCourierID uuid.UUID, wantCancellationReason CancellationReason) {
	t.Helper()
	want := shipmentState{status: wantStatus, holder: wantHolder, courierID: wantCourierID, cancellationReason: wantCancellationReason}
	if got != want {
		t.Errorf("state = %+v, want %+v", got, want)
	}
}

func TestNewShipmentState(t *testing.T) {
	courierID := uuid.New()

	t.Run("accepts every state a shipment can be in", func(t *testing.T) {
		testCases := []struct {
			name               string
			status             ShipmentStatus
			holder             ShipmentHolder
			courierID          uuid.UUID
			cancellationReason CancellationReason
		}{
			{"created, at the inventory", Created, HolderInventory, uuid.Nil, NoCancellationReason},
			{"in delivery with its courier", InDelivery, HolderCourier, courierID, NoCancellationReason},
			{"delivered by its courier", Delivered, HolderCourier, courierID, NoCancellationReason},
			{"lost by its courier", Lost, HolderCourier, courierID, NoCancellationReason},
			{"cancelled with the order before pickup", Cancelled, HolderInventory, uuid.Nil, OrderCancelled},
			{"cancelled with the order, still with the courier", Cancelled, HolderCourier, courierID, OrderCancelled},
			{"refused by the recipient, still with the courier", Cancelled, HolderCourier, courierID, RecipientRefused},
			{"recipient unavailable, still with the courier", Cancelled, HolderCourier, courierID, RecipientUnavailable},
			{"cancelled and returned to the inventory", Cancelled, HolderInventory, courierID, RecipientRefused},
		}
		for _, testCase := range testCases {
			state, err := newShipmentState(testCase.status, testCase.holder, testCase.courierID, testCase.cancellationReason)
			if err != nil {
				t.Errorf("%s: unexpected error: %v", testCase.name, err)
				continue
			}
			assertState(t, state, testCase.status, testCase.holder, testCase.courierID, testCase.cancellationReason)
		}
	})

	t.Run("rejects a state a shipment can't be in", func(t *testing.T) {
		testCases := []struct {
			name               string
			status             ShipmentStatus
			holder             ShipmentHolder
			courierID          uuid.UUID
			cancellationReason CancellationReason
		}{
			{"an unknown status", "Floating", HolderInventory, uuid.Nil, NoCancellationReason},
			{"an unknown holder", Created, "Somewhere", uuid.Nil, NoCancellationReason},
			{"created, held by a courier", Created, HolderCourier, uuid.Nil, NoCancellationReason},
			{"created, with a courier", Created, HolderInventory, courierID, NoCancellationReason},
			{"in delivery at the inventory", InDelivery, HolderInventory, courierID, NoCancellationReason},
			{"in delivery without a courier", InDelivery, HolderCourier, uuid.Nil, NoCancellationReason},
			{"delivered at the inventory", Delivered, HolderInventory, courierID, NoCancellationReason},
			{"delivered without a courier", Delivered, HolderCourier, uuid.Nil, NoCancellationReason},
			{"lost at the inventory", Lost, HolderInventory, courierID, NoCancellationReason},
			{"lost without a courier", Lost, HolderCourier, uuid.Nil, NoCancellationReason},
			{"cancelled, held by a courier that isn't there", Cancelled, HolderCourier, uuid.Nil, OrderCancelled},
			{"cancelled for no reason", Cancelled, HolderInventory, uuid.Nil, NoCancellationReason},
			{"cancelled for an unknown reason", Cancelled, HolderInventory, uuid.Nil, "Whatever"},
			{"refused by the recipient before any courier picked it up", Cancelled, HolderInventory, uuid.Nil, RecipientRefused},
			{"recipient unavailable before any courier picked it up", Cancelled, HolderInventory, uuid.Nil, RecipientUnavailable},
			{"in delivery, with a cancellation reason", InDelivery, HolderCourier, courierID, OrderCancelled},
			{"delivered, with a cancellation reason", Delivered, HolderCourier, courierID, RecipientRefused},
		}
		for _, testCase := range testCases {
			_, err := newShipmentState(testCase.status, testCase.holder, testCase.courierID, testCase.cancellationReason)
			if !errors.Is(err, ErrInconsistentShipment) {
				t.Errorf("%s: err = %v, want %v", testCase.name, err, ErrInconsistentShipment)
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
		assertState(t, state, InDelivery, HolderCourier, courierID, NoCancellationReason)
	})

	t.Run("rejects a missing courier, leaving the state unchanged", func(t *testing.T) {
		state := mustShipmentState(t, Created, HolderInventory, uuid.Nil)
		before := state

		err := state.startDelivery(uuid.Nil)

		assertRejected(t, err, ErrMissingCourier, state, before)
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
		assertState(t, state, Delivered, HolderCourier, courierID, NoCancellationReason)
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
		assertState(t, state, Lost, HolderCourier, courierID, NoCancellationReason)
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
		reason := OrderCancelled
		state := mustShipmentState(t, Created, HolderInventory, uuid.Nil)

		err := state.cancel(reason)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		assertState(t, state, Cancelled, HolderInventory, uuid.Nil, reason)
	})

	t.Run("after pickup keeps the goods with the courier, for the given reason", func(t *testing.T) {
		courierID := uuid.New()
		for _, reason := range []CancellationReason{OrderCancelled, RecipientRefused, RecipientUnavailable} {
			state := mustShipmentState(t, InDelivery, HolderCourier, courierID)

			err := state.cancel(reason)

			if err != nil {
				t.Fatalf("%s: unexpected error: %v", reason, err)
			}
			assertState(t, state, Cancelled, HolderCourier, courierID, reason)
		}
	})

	t.Run("rejects a reason only a courier can report before any courier picked the shipment up, leaving the state unchanged", func(t *testing.T) {
		state := mustShipmentState(t, Created, HolderInventory, uuid.Nil)
		before := state

		err := state.cancel(RecipientRefused)

		assertRejected(t, err, ErrInconsistentShipment, state, before)
	})

	t.Run("rejects a missing or unknown reason, leaving the state unchanged", func(t *testing.T) {
		for _, reason := range []CancellationReason{NoCancellationReason, "Whatever"} {
			state := mustShipmentState(t, InDelivery, HolderCourier, uuid.New())
			before := state

			err := state.cancel(reason)

			assertRejected(t, err, ErrInconsistentShipment, state, before)
		}
	})

	t.Run("rejects a shipment that already reached a terminal status, leaving the state unchanged", func(t *testing.T) {
		state := mustShipmentState(t, Delivered, HolderCourier, uuid.New())
		before := state

		err := state.cancel(OrderCancelled)

		assertRejected(t, err, ErrInvalidStatusTransition, state, before)
	})
}

func TestShipmentState_ReturnToInventory(t *testing.T) {
	t.Run("moves the goods back to the inventory, keeping the status, the courier and the reason", func(t *testing.T) {
		courierID := uuid.New()
		reason := RecipientUnavailable
		state := mustCancelledState(t, HolderCourier, courierID, reason)

		err := state.returnToInventory()

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		assertState(t, state, Cancelled, HolderInventory, courierID, reason)
	})

	t.Run("rejects a cancelled shipment whose goods are already at the inventory, leaving the state unchanged", func(t *testing.T) {
		state := mustCancelledState(t, HolderInventory, uuid.Nil, OrderCancelled)
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

func TestShipmentState_IsOpen(t *testing.T) {
	courierID := uuid.New()

	t.Run("true while the shipment can still end either way", func(t *testing.T) {
		for _, state := range []shipmentState{
			mustShipmentState(t, Created, HolderInventory, uuid.Nil),
			mustShipmentState(t, InDelivery, HolderCourier, courierID),
		} {
			if !state.isOpen() {
				t.Errorf("isOpen() = false for %+v, want true", state)
			}
		}
	})

	t.Run("false once the shipment has ended", func(t *testing.T) {
		for _, state := range []shipmentState{
			mustShipmentState(t, Delivered, HolderCourier, courierID),
			mustShipmentState(t, Lost, HolderCourier, courierID),
			mustCancelledState(t, HolderCourier, courierID, RecipientRefused),
		} {
			if state.isOpen() {
				t.Errorf("isOpen() = true for %+v, want false", state)
			}
		}
	})
}

func TestShipmentState_Change(t *testing.T) {
	t.Run("moves to a state a shipment can be in", func(t *testing.T) {
		courierID := uuid.New()
		state := mustShipmentState(t, Created, HolderInventory, uuid.Nil)

		err := state.change(InDelivery, HolderCourier, courierID, NoCancellationReason)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		assertState(t, state, InDelivery, HolderCourier, courierID, NoCancellationReason)
	})

	t.Run("rejects a state a shipment can't be in, leaving the state unchanged", func(t *testing.T) {
		state := mustShipmentState(t, Created, HolderInventory, uuid.Nil)
		before := state

		err := state.change(InDelivery, HolderInventory, uuid.Nil, NoCancellationReason)

		assertRejected(t, err, ErrInconsistentShipment, state, before)
	})
}

func TestCancellationReason_IsReportedByCourier(t *testing.T) {
	t.Run("true for what a courier finds out at the recipient's door", func(t *testing.T) {
		for _, reason := range []CancellationReason{RecipientRefused, RecipientUnavailable} {
			if !reason.IsReportedByCourier() {
				t.Errorf("IsReportedByCourier() = false for %q, want true", reason)
			}
		}
	})

	t.Run("false for a cancelled order, a missing and an unknown reason", func(t *testing.T) {
		for _, reason := range []CancellationReason{OrderCancelled, NoCancellationReason, "Whatever"} {
			if reason.IsReportedByCourier() {
				t.Errorf("IsReportedByCourier() = true for %q, want false", reason)
			}
		}
	})
}

package model

import (
	"fmt"
	"shippingservice/domain"
	"slices"

	"github.com/google/uuid"
)

// shipmentState is where a shipment is in its lifecycle: its status, who physically has the goods, and the courier
// it's assigned to (uuid.Nil until pickup). It keeps every rule about which states exist and how one leads to another
// in one place: newShipmentState checks a state loaded from the database, and every transition goes through change,
// which checks the new state the same way - so a state that breaks the rules can't come from either. A rejected
// transition leaves the state exactly as it was.
type shipmentState struct {
	status    ShipmentStatus
	holder    ShipmentHolder
	courierID uuid.UUID
}

func newShipmentState(status ShipmentStatus, holder ShipmentHolder, courierID uuid.UUID) (shipmentState, error) {
	if !isValidState(status, holder, courierID) {
		return shipmentState{}, fmt.Errorf("%w: status %s, holder %s, courier %s", domain.ErrInvalidInput, status, holder, courierID)
	}

	return shipmentState{status: status, holder: holder, courierID: courierID}, nil
}

// isValidState reports whether a shipment can be in status, held by holder and assigned to courierID: the goods leave
// the inventory only at pickup, which assigns the courier, and can come back to it only after a cancellation.
func isValidState(status ShipmentStatus, holder ShipmentHolder, courierID uuid.UUID) bool {
	if !status.isValid() || !holder.isValid() {
		return false
	}

	switch status {
	case Created:
		return holder == HolderInventory && courierID == uuid.Nil
	case InDelivery, Delivered, Lost:
		return holder == HolderCourier && courierID != uuid.Nil
	case Cancelled:
		// still on its way back (courier), or back at the inventory: cancelled before pickup (no courier) or returned
		return holder == HolderInventory || courierID != uuid.Nil
	default:
		return false
	}
}

// startDelivery is the pickup: the courier takes the goods from the inventory, and the shipment becomes theirs.
func (s *shipmentState) startDelivery(courierID uuid.UUID) error {
	if courierID == uuid.Nil {
		return domain.ErrInvalidInput
	}
	if err := s.requireStatus("start delivery", Created); err != nil {
		return err
	}

	return s.change(InDelivery, HolderCourier, courierID)
}

func (s *shipmentState) deliver() error {
	if err := s.requireStatus("deliver", InDelivery); err != nil {
		return err
	}

	return s.change(Delivered, s.holder, s.courierID)
}

func (s *shipmentState) lose() error {
	if err := s.requireStatus("lose", InDelivery); err != nil {
		return err
	}

	return s.change(Lost, s.holder, s.courierID)
}

// cancel keeps the goods where they are: with the inventory before pickup, with the courier after it.
func (s *shipmentState) cancel() error {
	if err := s.requireStatus("cancel", Created, InDelivery); err != nil {
		return err
	}

	return s.change(Cancelled, s.holder, s.courierID)
}

// returnToInventory records that the courier brought the goods of a cancelled shipment back. The status stays
// Cancelled - only the holder changes.
func (s *shipmentState) returnToInventory() error {
	if err := s.requireStatus("confirm returned to inventory", Cancelled); err != nil {
		return err
	}
	if s.holder != HolderCourier {
		return fmt.Errorf("%w: cannot confirm returned to inventory: expected holder %s but was %s", ErrInvalidStatusTransition, HolderCourier, s.holder)
	}

	return s.change(s.status, HolderInventory, s.courierID)
}

// change moves to the new state only if it's one a shipment can be in, so every transition is checked by the same
// rules as a state loaded from the database.
func (s *shipmentState) change(status ShipmentStatus, holder ShipmentHolder, courierID uuid.UUID) error {
	if !isValidState(status, holder, courierID) {
		return fmt.Errorf("%w: status %s, holder %s, courier %s", domain.ErrInvalidInput, status, holder, courierID)
	}

	s.status = status
	s.holder = holder
	s.courierID = courierID
	return nil
}

func (s *shipmentState) requireStatus(action string, expected ...ShipmentStatus) error {
	if slices.Contains(expected, s.status) {
		return nil
	}

	return fmt.Errorf("%w: cannot %s: expected status %v but was %s", ErrInvalidStatusTransition, action, expected, s.status)
}

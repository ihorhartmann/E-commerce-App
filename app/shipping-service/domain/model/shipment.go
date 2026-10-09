package model

import (
	"fmt"
	"shippingservice/domain"
	"shippingservice/domain/value"
	"time"

	"github.com/google/uuid"
)

type Shipment struct {
	id        uint64
	orderId   uint64
	address   value.Address
	state     shipmentState
	createdAt time.Time
	// updatedAt is the zero value (updatedAt.IsZero()) until the shipment's state actually
	// changes for the first time - see apply().
	updatedAt time.Time
	version   uint64
}

func NewExistingShipment(
	id uint64,
	orderId uint64,
	courierID uuid.UUID,
	address value.Address,
	status ShipmentStatus,
	holder ShipmentHolder,
	createdAt time.Time,
	updatedAt time.Time,
	version uint64,
) (*Shipment, error) {
	if id == 0 || orderId == 0 {
		return nil, domain.ErrInvalidInput
	}

	state, err := newShipmentState(status, holder, courierID)
	if err != nil {
		return nil, err
	}

	return &Shipment{
		id:        id,
		orderId:   orderId,
		address:   address,
		state:     state,
		createdAt: createdAt,
		updatedAt: updatedAt,
		version:   version,
	}, nil
}

// NewShipment creates a brand-new shipment in the initial Created status, with the goods still
// held by the inventory (not yet picked up by a courier).
func NewShipment(orderId uint64, address value.Address) (*Shipment, error) {
	if orderId == 0 {
		return nil, domain.ErrInvalidInput
	}

	state, err := newShipmentState(Created, HolderInventory, uuid.Nil)
	if err != nil {
		return nil, err
	}

	return &Shipment{
		orderId:   orderId,
		address:   address,
		state:     state,
		createdAt: time.Now(),
		// updatedAt stays zero-value (IsZero()) - nothing has been updated yet.
	}, nil
}

// StartDelivery is the pickup: the courier takes the goods from the inventory, and the shipment becomes theirs - only
// they may deliver it, report it lost or bring it back.
func (s *Shipment) StartDelivery(courierID uuid.UUID) error {
	return s.apply(s.state.startDelivery(courierID))
}

func (s *Shipment) Deliver() error {
	return s.apply(s.state.deliver())
}

func (s *Shipment) Lose() error {
	return s.apply(s.state.lose())
}

func (s *Shipment) Cancel() error {
	return s.apply(s.state.cancel())
}

// ConfirmReturnedToInventory records that the courier physically brought the goods back. It only
// changes holder, not status - Cancelled stays the terminal status either way.
func (s *Shipment) ConfirmReturnedToInventory() error {
	return s.apply(s.state.returnToInventory())
}

// apply records when a transition changed the state; a rejected transition left the state, and so the whole shipment,
// exactly as it was.
func (s *Shipment) apply(err error) error {
	if err != nil {
		return fmt.Errorf("shipment %d: %w", s.id, err)
	}

	s.updatedAt = time.Now()
	return nil
}

func (s *Shipment) IsAssignedTo(courierID uuid.UUID) bool {
	return s.state.courierID != uuid.Nil && s.state.courierID == courierID
}

func (s *Shipment) Id() uint64 {
	return s.id
}

func (s *Shipment) OrderId() uint64 {
	return s.orderId
}

func (s *Shipment) Address() value.Address {
	return s.address
}

func (s *Shipment) Status() ShipmentStatus {
	return s.state.status
}

func (s *Shipment) Holder() ShipmentHolder {
	return s.state.holder
}

func (s *Shipment) CreatedAt() time.Time {
	return s.createdAt
}

// UpdatedAt returns the zero value (check with UpdatedAt().IsZero()) until the shipment's state
// actually changes for the first time.
func (s *Shipment) UpdatedAt() time.Time {
	return s.updatedAt
}

func (s *Shipment) Version() uint64 {
	return s.version
}

// CourierID returns uuid.Nil until a courier picks the shipment up.
func (s *Shipment) CourierID() uuid.UUID {
	return s.state.courierID
}

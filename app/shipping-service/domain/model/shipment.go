package model

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"shippingservice/domain"
	"shippingservice/domain/value"
	"slices"
	"strings"
	"time"
)

type Shipment struct {
	id             uint64
	orderId        uint64
	trackingNumber string
	address        value.Address
	status         ShipmentStatus
	holder         ShipmentHolder
	createdAt      time.Time
	// updatedAt is the zero value (updatedAt.IsZero()) until the shipment's status actually
	// changes for the first time - see changeState().
	updatedAt time.Time
	version   uint64
}

func NewExistingShipment(
	id uint64,
	orderId uint64,
	trackingNumber string,
	address value.Address,
	status ShipmentStatus,
	holder ShipmentHolder,
	createdAt time.Time,
	updatedAt time.Time,
	version uint64,
) (*Shipment, error) {
	if id == 0 || !status.isValid() || !holder.isValid() || !holderMatchesStatus(status, holder) {
		return nil, domain.ErrInvalidInput
	}
	if err := validateShipment(orderId, trackingNumber); err != nil {
		return nil, err
	}

	return &Shipment{
		id:             id,
		orderId:        orderId,
		trackingNumber: trackingNumber,
		address:        address,
		status:         status,
		holder:         holder,
		createdAt:      createdAt,
		updatedAt:      updatedAt,
		version:        version,
	}, nil
}

// NewShipment creates a brand-new shipment in the initial Created status, with the goods still
// held by the inventory (not yet picked up by a courier). It generates its own tracking number.
func NewShipment(orderId uint64, address value.Address) (*Shipment, error) {
	if orderId == 0 {
		return nil, domain.ErrInvalidInput
	}

	trackingNumber, err := generateTrackingNumber(orderId)
	if err != nil {
		return nil, err
	}

	return &Shipment{
		orderId:        orderId,
		trackingNumber: trackingNumber,
		address:        address,
		status:         Created,
		holder:         HolderInventory,
		createdAt:      time.Now(),
		// updatedAt stays zero-value (IsZero()) - nothing has been updated yet.
	}, nil
}

func generateTrackingNumber(orderId uint64) (string, error) {
	suffix := make([]byte, 4)
	if _, err := rand.Read(suffix); err != nil {
		return "", fmt.Errorf("failed to generate tracking number: %w", err)
	}

	return fmt.Sprintf("%d%s", orderId, strings.ToUpper(hex.EncodeToString(suffix))), nil
}

// holderMatchesStatus reports whether a shipment in status can be held by holder: the goods leave
// the inventory only at pickup (StartDelivery) and can come back to it only after a cancellation.
func holderMatchesStatus(status ShipmentStatus, holder ShipmentHolder) bool {
	switch status {
	case Created:
		return holder == HolderInventory
	case InDelivery, Delivered, Lost:
		return holder == HolderCourier
	case Cancelled:
		// cancelled before pickup or already returned (inventory), or still on its way back (courier)
		return true
	default:
		return false
	}
}

func validateShipment(orderId uint64, trackingNumber string) error {
	if orderId == 0 || strings.TrimSpace(trackingNumber) == "" {
		return domain.ErrInvalidInput
	}

	return nil
}

func (s *Shipment) StartDelivery() error {
	if err := s.requireStatus("start delivery", Created); err != nil {
		return err
	}

	s.changeState(InDelivery, HolderCourier)
	return nil
}

func (s *Shipment) Deliver() error {
	if err := s.requireStatus("deliver", InDelivery); err != nil {
		return err
	}

	s.changeState(Delivered, s.holder)
	return nil
}

func (s *Shipment) Lose() error {
	if err := s.requireStatus("lose", InDelivery); err != nil {
		return err
	}

	s.changeState(Lost, s.holder)
	return nil
}

func (s *Shipment) Cancel() error {
	if err := s.requireStatus("cancel", Created, InDelivery); err != nil {
		return err
	}

	s.changeState(Cancelled, s.holder)
	return nil
}

// ConfirmReturnedToInventory records that the courier physically brought the goods back. It only
// changes holder, not status - Cancelled stays the terminal status either way.
func (s *Shipment) ConfirmReturnedToInventory() error {
	if err := s.requireStatus("confirm returned to inventory", Cancelled); err != nil {
		return err
	}
	if err := s.requireHolder("confirm returned to inventory", HolderCourier); err != nil {
		return err
	}

	s.changeState(s.status, HolderInventory)
	return nil
}

func (s *Shipment) requireStatus(action string, expected ...ShipmentStatus) error {
	if slices.Contains(expected, s.status) {
		return nil
	}

	return fmt.Errorf("%w: cannot %s shipment %d: expected status %v but was %s", ErrInvalidStatusTransition, action, s.id, expected, s.status)
}

func (s *Shipment) requireHolder(action string, expected ShipmentHolder) error {
	if s.holder == expected {
		return nil
	}

	return fmt.Errorf("%w: cannot %s shipment %d: expected holder %s but was %s", ErrInvalidStatusTransition, action, s.id, expected, s.holder)
}

func (s *Shipment) changeState(status ShipmentStatus, holder ShipmentHolder) {
	s.status = status
	s.holder = holder
	s.updatedAt = time.Now()
}

func (s *Shipment) Id() uint64 {
	return s.id
}

func (s *Shipment) OrderId() uint64 {
	return s.orderId
}

func (s *Shipment) TrackingNumber() string {
	return s.trackingNumber
}

func (s *Shipment) Address() value.Address {
	return s.address
}

func (s *Shipment) Status() ShipmentStatus {
	return s.status
}

func (s *Shipment) Holder() ShipmentHolder {
	return s.holder
}

func (s *Shipment) CreatedAt() time.Time {
	return s.createdAt
}

// UpdatedAt returns the zero value (check with UpdatedAt().IsZero()) until the shipment's status
// actually changes for the first time.
func (s *Shipment) UpdatedAt() time.Time {
	return s.updatedAt
}

func (s *Shipment) Version() uint64 {
	return s.version
}

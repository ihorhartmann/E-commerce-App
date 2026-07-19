package model

import (
	"fmt"
	"shippingservice/domain/value"
	"slices"
	"time"
)

type Shipment struct {
	id             uint64
	orderId        uint64
	trackingNumber string
	address        value.Address
	status         ShipmentStatus
	createdAt      time.Time
	updatedAt      time.Time
}

func NewExistingShipment(
	id uint64,
	orderId uint64,
	trackingNumber string,
	address value.Address,
	status ShipmentStatus,
	createdAt time.Time,
	updatedAt time.Time,
) *Shipment {
	return &Shipment{
		id:             id,
		orderId:        orderId,
		trackingNumber: trackingNumber,
		address:        address,
		status:         status,
		createdAt:      createdAt,
		updatedAt:      updatedAt,
	}
}

// NewShipment creates a brand-new shipment in the initial Created status.
func NewShipment(orderId uint64, trackingNumber string, address value.Address) *Shipment {
	now := time.Now()

	return &Shipment{
		orderId:        orderId,
		trackingNumber: trackingNumber,
		address:        address,
		status:         Created,
		createdAt:      now,
		updatedAt:      now,
	}
}

func (s *Shipment) StartDelivery() (*Shipment, error) {
	if err := s.requireStatus("start delivery", Created); err != nil {
		return nil, err
	}

	return s.copy(InDelivery), nil
}

func (s *Shipment) Deliver() (*Shipment, error) {
	if err := s.requireStatus("deliver", InDelivery); err != nil {
		return nil, err
	}

	return s.copy(Delivered), nil
}

func (s *Shipment) Lose() (*Shipment, error) {
	if err := s.requireStatus("lose", InDelivery); err != nil {
		return nil, err
	}

	return s.copy(Lost), nil
}

func (s *Shipment) Cancel() (*Shipment, error) {
	if err := s.requireStatus("cancel", Created, InDelivery); err != nil {
		return nil, err
	}

	return s.copy(Cancelled), nil
}

func (s *Shipment) ReturnToInventory() (*Shipment, error) {
	if err := s.requireStatus("return to inventory", Cancelled); err != nil {
		return nil, err
	}

	return s.copy(ReturnedToInventory), nil
}

func (s *Shipment) requireStatus(action string, expected ...ShipmentStatus) error {
	if slices.Contains(expected, s.status) {
		return nil
	}

	return fmt.Errorf("%w: cannot %s shipment %d: expected status %v but was %s", ErrInvalidStatusTransition, action, s.id, expected, s.status)
}

func (s *Shipment) copy(status ShipmentStatus) *Shipment {
	return &Shipment{
		id:             s.id,
		orderId:        s.orderId,
		trackingNumber: s.trackingNumber,
		address:        s.address,
		status:         status,
		createdAt:      s.createdAt,
		updatedAt:      time.Now(),
	}
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

func (s *Shipment) CreatedAt() time.Time {
	return s.createdAt
}

func (s *Shipment) UpdatedAt() time.Time {
	return s.updatedAt
}

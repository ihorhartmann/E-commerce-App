package model

import (
	"shippingservice/domain/value"
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

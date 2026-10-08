package entity

import (
	"time"

	"shippingservice/domain/model"
	"shippingservice/domain/value"

	"github.com/google/uuid"
)

// Shipment is the shipments table row shape.
type Shipment struct {
	ID             uint64     `gorm:"primaryKey"`
	OrderID        uint64     `gorm:"uniqueIndex:uq_shipments_order_id;not null"`
	CustomerID     uuid.UUID  `gorm:"type:uuid;not null"`
	CourierID      *uuid.UUID `gorm:"type:uuid"`
	TrackingNumber string     `gorm:"uniqueIndex;not null"`
	City           string     `gorm:"not null"`
	PostalCode     string     `gorm:"not null"`
	Line1          string     `gorm:"not null"`
	Line2          string     `gorm:"not null"`
	Status         string     `gorm:"not null"`
	Holder         string     `gorm:"not null"`
	CreatedAt      time.Time  `gorm:"not null"`
	UpdatedAt      *time.Time
	Version        uint64 `gorm:"not null;default:0"`
}

func (Shipment) TableName() string {
	return "shipments"
}

func ToShipmentEntity(shipment *model.Shipment) *Shipment {
	address := shipment.Address()

	return &Shipment{
		ID:             shipment.Id(),
		OrderID:        shipment.OrderId(),
		CustomerID:     shipment.CustomerID(),
		CourierID:      toNillableUUID(shipment.CourierID()),
		TrackingNumber: shipment.TrackingNumber(),
		City:           address.City(),
		PostalCode:     address.PostalCode(),
		Line1:          address.Line1(),
		Line2:          address.Line2(),
		Status:         string(shipment.Status()),
		Holder:         string(shipment.Holder()),
		CreatedAt:      shipment.CreatedAt(),
		UpdatedAt:      toNillableTime(shipment.UpdatedAt()),
		Version:        shipment.Version(),
	}
}

func FromShipmentEntity(shipment *Shipment) (*model.Shipment, error) {
	address, err := value.NewAddress(shipment.City, shipment.PostalCode, shipment.Line1, shipment.Line2)
	if err != nil {
		return nil, err
	}

	return model.NewExistingShipment(
		shipment.ID,
		shipment.OrderID,
		shipment.CustomerID,
		fromNillableUUID(shipment.CourierID),
		shipment.TrackingNumber,
		address,
		model.ShipmentStatus(shipment.Status),
		model.ShipmentHolder(shipment.Holder),
		shipment.CreatedAt,
		fromNillableTime(shipment.UpdatedAt),
		shipment.Version,
	)
}

// toNillableTime converts the domain's zero-value-means-unset time.Time into a nullable *time.Time
// for storage, since the DB column is nullable (unlike time.Time, which always holds some value).
func toNillableTime(t time.Time) *time.Time {
	if t.IsZero() {
		return nil
	}
	return &t
}

// fromNillableTime converts a nullable stored timestamp back into the domain's zero-value-means-unset
// time.Time.
func fromNillableTime(t *time.Time) time.Time {
	if t == nil {
		return time.Time{}
	}
	return *t
}

func toNillableUUID(id uuid.UUID) *uuid.UUID {
	if id == uuid.Nil {
		return nil
	}
	return &id
}

func fromNillableUUID(id *uuid.UUID) uuid.UUID {
	if id == nil {
		return uuid.Nil
	}
	return *id
}

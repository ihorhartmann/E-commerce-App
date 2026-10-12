package entity

import (
	"time"

	"shippingservice/domain/model"
	"shippingservice/domain/value"

	"github.com/google/uuid"
)

// Shipment is the shipments table row shape.
type Shipment struct {
	ID      uint64 `gorm:"primaryKey"`
	OrderID uint64 `gorm:"uniqueIndex:uq_shipments_order_id;not null"`
	// the two composite indexes serve the courier lists: available (status, created_at) and in progress (courier_id,
	// status, created_at)
	CourierID          *uuid.UUID `gorm:"type:uuid;index:idx_shipments_courier_id_status_created_at,priority:1"`
	City               string     `gorm:"not null"`
	PostalCode         string     `gorm:"not null"`
	Line1              string     `gorm:"not null"`
	Line2              string     `gorm:"not null"`
	Status             string     `gorm:"not null;index:idx_shipments_status_created_at,priority:1;index:idx_shipments_courier_id_status_created_at,priority:2"`
	Holder             string     `gorm:"not null"`
	CancellationReason *string
	CreatedAt          time.Time `gorm:"not null;index:idx_shipments_status_created_at,priority:2;index:idx_shipments_courier_id_status_created_at,priority:3"`
	UpdatedAt          *time.Time
	Version            uint64 `gorm:"not null;default:0"`
}

func (Shipment) TableName() string {
	return "shipments"
}

func ToShipmentEntity(shipment *model.Shipment) *Shipment {
	address := shipment.Address()

	return &Shipment{
		ID:                 shipment.Id(),
		OrderID:            shipment.OrderId(),
		CourierID:          toNillableUUID(shipment.CourierID()),
		City:               address.City(),
		PostalCode:         address.PostalCode(),
		Line1:              address.Line1(),
		Line2:              address.Line2(),
		Status:             string(shipment.Status()),
		Holder:             string(shipment.Holder()),
		CancellationReason: toNillableString(string(shipment.CancellationReason())),
		CreatedAt:          shipment.CreatedAt(),
		UpdatedAt:          toNillableTime(shipment.UpdatedAt()),
		Version:            shipment.Version(),
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
		fromNillableUUID(shipment.CourierID),
		address,
		model.ShipmentStatus(shipment.Status),
		model.ShipmentHolder(shipment.Holder),
		model.CancellationReason(fromNillableString(shipment.CancellationReason)),
		shipment.CreatedAt,
		fromNillableTime(shipment.UpdatedAt),
		shipment.Version,
	)
}

func FromShipmentEntities(shipments []*Shipment) ([]*model.Shipment, error) {
	result := make([]*model.Shipment, 0, len(shipments))
	for _, shipment := range shipments {
		converted, err := FromShipmentEntity(shipment)
		if err != nil {
			return nil, err
		}
		result = append(result, converted)
	}

	return result, nil
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

func toNillableString(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func fromNillableString(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

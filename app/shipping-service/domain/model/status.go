package model

import "errors"

type ShipmentStatus string

const (
	Delivered ShipmentStatus = "Delivered"
	Cancelled ShipmentStatus = "Cancelled"
)

var statuses = map[ShipmentStatus]struct{}{
	Delivered: {},
	Cancelled: {},
}

func ToShipmentStatus(status string) (ShipmentStatus, error) {
	if _, ok := statuses[ShipmentStatus(status)]; !ok {
		return "", errors.New("invalid shipment status")
	}
	return ShipmentStatus(status), nil
}

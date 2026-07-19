package model

type ShipmentStatus string

const (
	Created    ShipmentStatus = "Created"
	InDelivery ShipmentStatus = "In Delivery"
	Delivered  ShipmentStatus = "Delivered" // terminal
	Cancelled  ShipmentStatus = "Cancelled" // terminal - client cancelled order or not at home
	Lost       ShipmentStatus = "Lost"      // terminal - shipment lost or damaged
)

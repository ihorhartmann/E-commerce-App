package model

type ShipmentStatus string

const (
	Created             ShipmentStatus = "Created"
	InDelivery          ShipmentStatus = "In Delivery"
	Delivered           ShipmentStatus = "Delivered"
	Cancelled           ShipmentStatus = "Cancelled"           // client cancelled order or not at home
	Lost                ShipmentStatus = "Lost"                // shipment lost
	ReturnedToInventory ShipmentStatus = "ReturnedToInventory" // courier returned the goods to the inventory after cancellation
)

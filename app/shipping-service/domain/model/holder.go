package model

// ShipmentHolder identifies who currently physically has the goods, tracked
// independently of ShipmentStatus across the whole lifecycle.
type ShipmentHolder string

const (
	HolderInventory ShipmentHolder = "Inventory"
	HolderCourier   ShipmentHolder = "Courier"
)

func (h ShipmentHolder) isValid() bool {
	switch h {
	case HolderInventory, HolderCourier:
		return true
	default:
		return false
	}
}

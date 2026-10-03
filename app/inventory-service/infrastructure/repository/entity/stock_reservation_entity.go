package entity

import "inventoryservice/domain/model"

// StockReservation is the stock_reservations table row shape.
type StockReservation struct {
	ID        uint64 `gorm:"primaryKey"`
	ProductID uint64 `gorm:"index;not null"`
	OrderID   uint64 `gorm:"index;not null"`
	Quantity  uint   `gorm:"not null"`
	Holder    string `gorm:"not null"`
	Version   uint64 `gorm:"not null;default:0"`
}

func (StockReservation) TableName() string {
	return "stock_reservations"
}

func ToStockReservationEntity(reservation *model.StockReservation) *StockReservation {
	return &StockReservation{
		ID:        reservation.ID(),
		ProductID: reservation.ProductID(),
		OrderID:   reservation.OrderID(),
		Quantity:  reservation.Quantity(),
		Holder:    string(reservation.Holder()),
		Version:   reservation.Version(),
	}
}

func FromStockReservationEntity(reservation *StockReservation) (*model.StockReservation, error) {
	return model.NewExistingStockReservation(
		reservation.ID,
		reservation.ProductID,
		reservation.OrderID,
		reservation.Quantity,
		model.ReservationHolder(reservation.Holder),
		reservation.Version,
	)
}

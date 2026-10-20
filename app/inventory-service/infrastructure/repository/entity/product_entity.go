package entity

import (
	"log/slog"

	"inventoryservice/domain/model"
	"inventoryservice/domain/values"

	"github.com/shopspring/decimal"
)

// Product is the products table row shape.
type Product struct {
	ID            uint64          `gorm:"primaryKey"`
	Name          string          `gorm:"not null"`
	Description   string          `gorm:"not null"`
	PriceAmount   decimal.Decimal `gorm:"type:numeric(19,2);not null"`
	PriceCurrency string          `gorm:"not null"`
	Quantity      uint            `gorm:"not null"`
	Reserved      uint            `gorm:"not null;default:0"`
	Version       uint64          `gorm:"not null;default:0"`
}

func (Product) TableName() string {
	return "products"
}

func ToProductEntity(product *model.Product) *Product {
	return &Product{
		ID:            product.ID(),
		Name:          product.Name(),
		Description:   product.Description(),
		PriceAmount:   product.Price().Amount(),
		PriceCurrency: string(product.Price().Currency()),
		Quantity:      product.Quantity(),
		Reserved:      product.Reserved(),
		Version:       product.Version(),
	}
}

func FromProductEntity(product *Product) (*model.Product, error) {
	price, err := values.NewMoney(product.PriceAmount, values.Currency(product.PriceCurrency))
	if err != nil {
		// a stored price that isn't valid money is corrupted data, not an invalid request
		return nil, model.ErrInconsistentProduct.
			WithInternalMessage("stored product price is not valid money").
			WithAttrs(slog.Uint64("productID", product.ID)).
			WithCause(err)
	}

	return model.NewExistingProduct(product.ID, product.Name, product.Description, price, product.Quantity, product.Reserved, product.Version)
}

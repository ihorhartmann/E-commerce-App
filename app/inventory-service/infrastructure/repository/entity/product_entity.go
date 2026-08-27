package entity

import (
	"inventoryservice/domain/model"
	"inventoryservice/domain/values"
)

// Product is the products table row shape.
type Product struct {
	ID            uint64  `gorm:"primaryKey"`
	Name          string  `gorm:"not null"`
	Description   string  `gorm:"not null"`
	PriceAmount   float64 `gorm:"not null"`
	PriceCurrency string  `gorm:"not null"`
	Quantity      uint    `gorm:"not null"`
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
	}
}

func FromProductEntity(product *Product) (*model.Product, error) {
	price, err := values.NewMoney(product.PriceAmount, values.Currency(product.PriceCurrency))
	if err != nil {
		return nil, err
	}

	return model.NewExistingProduct(product.ID, product.Name, product.Description, price, product.Quantity)
}

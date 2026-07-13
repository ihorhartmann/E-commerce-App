package model

import (
	"inventoryservice/entities"
	"strings"
)

type Product struct {
	id          uint64
	name        string
	description string
	price       float64
	onHand      uint
	reserved    uint
}

func NewProduct(id uint64, name, description string, price float64, onHand, reserved uint) (*Product, error) {
	if err := validateProduct(name, price, onHand, reserved); err != nil {
		return nil, err
	}

	return &Product{
		id:          id,
		name:        name,
		description: description,
		price:       price,
		onHand:      onHand,
		reserved:    reserved,
	}, nil
}

func NewExistingProduct(id uint64, name, description string, price float64, onHand, reserved uint) (*Product, error) {
	if id == 0 {
		return nil, entities.ErrInvalidInput
	}

	return NewProduct(id, name, description, price, onHand, reserved)
}

func validateProduct(name string, price float64, onHand, reserved uint) error {
	if strings.TrimSpace(name) == "" || price < 0 || reserved > onHand {
		return entities.ErrInvalidInput
	}

	return nil
}

func (p *Product) ID() uint64 {
	return p.id
}

func (p *Product) Name() string {
	return p.name
}

func (p *Product) Description() string {
	return p.description
}

func (p *Product) Price() float64 {
	return p.price
}

func (p *Product) OnHand() uint {
	return p.onHand
}

func (p *Product) Reserved() uint {
	return p.reserved
}

func (p *Product) Available() uint {
	return p.onHand - p.reserved
}

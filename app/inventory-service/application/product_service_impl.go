package application

import (
	"context"
	"inventoryservice/application/dto"
	"inventoryservice/application/port/in"
	"inventoryservice/application/port/out/repository"
	"inventoryservice/domain"

	"inventoryservice/domain/model"
)

type ProductServiceImpl struct {
	in.ProductService
	productRepository     repository.ProductRepository
	reservationRepository repository.ReservationRepository
}

func NewProductService(
	products repository.ProductRepository,
	reservations repository.ReservationRepository,
) in.ProductService {
	return &ProductServiceImpl{productRepository: products, reservationRepository: reservations}
}

func (ps *ProductServiceImpl) Create(ctx context.Context, product *model.Product) (*model.Product, error) {
	return ps.productRepository.Create(ctx, product)
}

func (ps *ProductServiceImpl) Update(ctx context.Context, product *model.Product) (*model.Product, error) {
	updated, err := ps.productRepository.Update(ctx, product)
	if err != nil {
		return nil, err
	}

	return ps.attachReservations(ctx, updated)
}

func (ps *ProductServiceImpl) Get(ctx context.Context, id uint64) (*model.Product, error) {
	if id == 0 {
		return nil, domain.ErrInvalidInput
	}

	product, err := ps.productRepository.GetByID(ctx, id)
	if err != nil {
		return nil, err
	}

	return ps.attachReservations(ctx, product)
}

// attachReservations attaches a product's current stock reservations, so Reserved()/Available() on the
// returned product reflect real state instead of always reading as unreserved - callers building a
// response from the product (e.g. the HTTP DTO) need this, since Product itself only carries
// reservations when explicitly given them (see Product.WithStockReservations).
// TODO may be replace with real DB join
func (ps *ProductServiceImpl) attachReservations(ctx context.Context, product *model.Product) (*model.Product, error) {
	reservations, err := ps.reservationRepository.FindByProductIDs(ctx, []uint64{product.ID()})
	if err != nil {
		return nil, err
	}

	return product.WithStockReservations(reservations), nil
}

func (ps *ProductServiceImpl) Delete(ctx context.Context, id uint64) error {
	if id == 0 {
		return domain.ErrInvalidInput
	}

	return ps.productRepository.Delete(ctx, id)
}

// TODO make it tx and try to bulk update products in the future
func (ps *ProductServiceImpl) Restock(ctx context.Context, items []dto.ReservationDTO) error {
	for _, item := range items {
		product, err := ps.productRepository.GetByID(ctx, item.ProductID)
		if err != nil {
			return err
		}

		if _, err = ps.productRepository.Update(ctx, product.Restock(item.Quantity)); err != nil {
			return err
		}
	}

	return nil
}

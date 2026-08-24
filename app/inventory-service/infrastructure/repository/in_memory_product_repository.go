package repository

import (
	"context"
	"fmt"
	"inventoryservice/application/port/out/repository"
	"sync"

	"inventoryservice/domain/model"
	"inventoryservice/domain/values"
)

type inMemoryProductRepository struct {
	repository.ProductRepository
	mu       sync.RWMutex
	products map[uint64]*model.Product
	nextID   uint64
}

func NewInMemoryProductRepository() repository.ProductRepository {
	products := make(map[uint64]*model.Product)

	defaultProducts := []*model.Product{
		checkError(model.NewExistingProduct(1, "Laptop", "Portable workstation", checkMoney(values.NewMoney(1299.99, values.USD)), 10)),
		checkError(model.NewExistingProduct(2, "Keyboard", "Mechanical keyboard", checkMoney(values.NewMoney(149.99, values.USD)), 25)),
		checkError(model.NewExistingProduct(3, "Mouse", "Wireless mouse", checkMoney(values.NewMoney(79.99, values.USD)), 40)),
	}

	for _, product := range defaultProducts {
		products[product.ID()] = product
	}

	return &inMemoryProductRepository{
		products: products,
		nextID:   4,
	}
}

func (r *inMemoryProductRepository) Create(_ context.Context, product *model.Product) (*model.Product, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	created, err := model.NewExistingProduct(
		r.nextID,
		product.Name(),
		product.Description(),
		product.Price(),
		product.Quantity(),
	)
	if err != nil {
		return nil, err
	}

	r.products[created.ID()] = created
	r.nextID++

	return created, nil
}

func (r *inMemoryProductRepository) Update(_ context.Context, product *model.Product) (*model.Product, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if _, ok := r.products[product.ID()]; !ok {
		return nil, repository.ErrProductNotFound
	}

	r.products[product.ID()] = product
	return product, nil
}

func (r *inMemoryProductRepository) GetByID(_ context.Context, id uint64) (*model.Product, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	product, ok := r.products[id]
	if !ok {
		return nil, repository.ErrProductNotFound
	}

	return product, nil
}

func (r *inMemoryProductRepository) GetByIDs(_ context.Context, ids []uint64) ([]*model.Product, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	found := make([]*model.Product, 0, len(ids))
	for _, id := range ids {
		product, ok := r.products[id]
		if !ok {
			return nil, fmt.Errorf("%w: product %d", repository.ErrProductNotFound, id)
		}

		found = append(found, product)
	}

	return found, nil
}

func (r *inMemoryProductRepository) Delete(_ context.Context, id uint64) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if _, ok := r.products[id]; !ok {
		return repository.ErrProductNotFound
	}

	delete(r.products, id)
	return nil
}

func checkError(product *model.Product, err error) *model.Product {
	if err != nil {
		panic(err)
	}

	return product
}

func checkMoney(money values.Money, err error) values.Money {
	if err != nil {
		panic(err)
	}

	return money
}

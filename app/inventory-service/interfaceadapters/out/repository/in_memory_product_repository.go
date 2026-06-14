package repository

import (
	"context"
	"inventoryservice/usecases/ports/repository"
	"sync"

	"inventoryservice/entities/model"
)

type inMemoryProductRepository struct {
	mu       sync.RWMutex
	products map[uint64]*model.Product
	nextID   uint64
}

func NewInMemoryProductRepository() repository.ProductRepository {
	products := make(map[uint64]*model.Product)

	defaultProducts := []*model.Product{
		checkError(model.NewExistingProduct(1, "Laptop", "Portable workstation", 1299.99, 10, 2)),
		checkError(model.NewExistingProduct(2, "Keyboard", "Mechanical keyboard", 149.99, 25, 5)),
		checkError(model.NewExistingProduct(3, "Mouse", "Wireless mouse", 79.99, 40, 4)),
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
		product.OnHand(),
		product.Reserved(),
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

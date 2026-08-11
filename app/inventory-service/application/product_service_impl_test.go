package application

import (
	"context"
	"errors"
	"reflect"
	"testing"

	"go.uber.org/mock/gomock"

	"inventoryservice/application/dto"
	"inventoryservice/application/port/out/repository/mocks"
	"inventoryservice/domain"
	"inventoryservice/domain/model"
)

func TestProductServiceImpl_Create(t *testing.T) {
	ctrl := gomock.NewController(t)
	newProduct := testNewProduct(t, 10)     // not yet persisted, id 0
	createdProduct := testProduct(t, 1, 10) // what the repository returns once it assigns an id
	productRepo := mocks.NewMockProductRepository(ctrl)
	productRepo.EXPECT().Create(gomock.Any(), newProduct).Return(createdProduct, nil).Times(1)

	service := NewProductService(productRepo)

	got, err := service.Create(context.Background(), newProduct)

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !reflect.DeepEqual(got, createdProduct) {
		t.Errorf("Create() = %+v, want %+v", got, createdProduct)
	}
}

func TestProductServiceImpl_Update(t *testing.T) {
	ctrl := gomock.NewController(t)
	input := testProduct(t, 1, 10)
	updatedProduct := testProduct(t, 1, 20) // what the repository actually persisted and returned
	productRepo := mocks.NewMockProductRepository(ctrl)
	productRepo.EXPECT().Update(gomock.Any(), input).Return(updatedProduct, nil).Times(1)

	service := NewProductService(productRepo)

	got, err := service.Update(context.Background(), input)

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !reflect.DeepEqual(got, updatedProduct) {
		t.Errorf("Update() = %+v, want %+v", got, updatedProduct)
	}
}

func TestProductServiceImpl_Get(t *testing.T) {
	t.Run("returns the product from the repository", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		product := testProduct(t, 1, 10)
		productRepo := mocks.NewMockProductRepository(ctrl)
		productRepo.EXPECT().GetByID(gomock.Any(), uint64(1)).Return(product, nil).Times(1)

		service := NewProductService(productRepo)

		got, err := service.Get(context.Background(), 1)

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if !reflect.DeepEqual(got, product) {
			t.Errorf("Get() = %+v, want %+v", got, product)
		}
	})

	t.Run("rejects a zero id without calling the repository", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		productRepo := mocks.NewMockProductRepository(ctrl)
		productRepo.EXPECT().GetByID(gomock.Any(), gomock.Any()).Times(0)

		service := NewProductService(productRepo)

		_, err := service.Get(context.Background(), 0)

		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})
}

func TestProductServiceImpl_Delete(t *testing.T) {
	t.Run("deletes the product by id", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		productRepo := mocks.NewMockProductRepository(ctrl)
		productRepo.EXPECT().Delete(gomock.Any(), uint64(1)).Return(nil).Times(1)

		service := NewProductService(productRepo)

		if err := service.Delete(context.Background(), 1); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
	})

	t.Run("rejects a zero id without calling the repository", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		productRepo := mocks.NewMockProductRepository(ctrl)
		productRepo.EXPECT().Delete(gomock.Any(), gomock.Any()).Times(0)

		service := NewProductService(productRepo)

		err := service.Delete(context.Background(), 0)

		if !errors.Is(err, domain.ErrInvalidInput) {
			t.Errorf("err = %v, want %v", err, domain.ErrInvalidInput)
		}
	})
}

func TestProductServiceImpl_Restock(t *testing.T) {
	t.Run("adds the requested quantity back to each product", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		p1 := testProduct(t, 1, 5)
		p2 := testProduct(t, 2, 8)
		productRepo := mocks.NewMockProductRepository(ctrl)

		productRepo.EXPECT().GetByID(gomock.Any(), uint64(1)).Return(p1, nil).Times(1)
		productRepo.EXPECT().GetByID(gomock.Any(), uint64(2)).Return(p2, nil).Times(1)

		var updated []*model.Product
		productRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(2).DoAndReturn(
			func(ctx context.Context, p *model.Product) (*model.Product, error) {
				updated = append(updated, p)
				return p, nil
			},
		)

		service := NewProductService(productRepo)

		items := []dto.ReservationDTO{{ProductID: 1, Quantity: 3}, {ProductID: 2, Quantity: 2}}
		if err := service.Restock(context.Background(), items); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if len(updated) != 2 {
			t.Fatalf("Update called %d times, want 2", len(updated))
		}
		if updated[0].Quantity() != 8 {
			t.Errorf("product 1 quantity = %d, want 8", updated[0].Quantity())
		}
		if updated[1].Quantity() != 10 {
			t.Errorf("product 2 quantity = %d, want 10", updated[1].Quantity())
		}
	})

	t.Run("propagates an error fetching a product and stops", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		wantErr := errors.New("db down")
		productRepo := mocks.NewMockProductRepository(ctrl)
		productRepo.EXPECT().GetByID(gomock.Any(), uint64(1)).Return(nil, wantErr).Times(1)
		productRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)

		service := NewProductService(productRepo)

		err := service.Restock(context.Background(), []dto.ReservationDTO{{ProductID: 1, Quantity: 1}})

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}

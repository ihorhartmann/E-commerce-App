package application

import (
	"context"
	"errors"
	"testing"

	"go.uber.org/mock/gomock"

	"inventoryservice/application/dto"
	"inventoryservice/application/port/out/repository/mocks"
	"inventoryservice/domain/model"
	"inventoryservice/domain/values"
)

func testMoney(t *testing.T) values.Money {
	t.Helper()
	money, err := values.NewMoney(10.0, values.USD)
	if err != nil {
		t.Fatalf("unexpected error building test money: %v", err)
	}
	return money
}

func testProduct(t *testing.T, id uint64, quantity uint) *model.Product {
	t.Helper()
	product, err := model.NewExistingProduct(id, "Widget", "a widget", testMoney(t), quantity)
	if err != nil {
		t.Fatalf("unexpected error building test product: %v", err)
	}
	return product
}

// testNewProduct builds a not-yet-persisted product (id 0), as it exists before a repository
// assigns it a real id on Create.
func testNewProduct(t *testing.T, quantity uint) *model.Product {
	t.Helper()
	product, err := model.NewProduct(0, "Widget", "a widget", testMoney(t), quantity)
	if err != nil {
		t.Fatalf("unexpected error building test product: %v", err)
	}
	return product
}

func testReservation(t *testing.T, id, productID, orderID uint64, quantity uint, holder model.ReservationHolder) *model.StockReservation {
	t.Helper()
	reservation, err := model.NewStockReservation(id, productID, orderID, quantity, holder)
	if err != nil {
		t.Fatalf("unexpected error building test reservation: %v", err)
	}
	return reservation
}

func TestReservationServiceImpl_ReserveItems(t *testing.T) {
	t.Run("creates reservations when enough stock is available", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		product := testProduct(t, 1, 10)
		productRepo := mocks.NewMockProductRepository(ctrl)
		reservationRepo := mocks.NewMockReservationRepository(ctrl)

		productRepo.EXPECT().GetByIDs(gomock.Any(), []uint64{1}).Return([]*model.Product{product}, nil).Times(1)
		reservationRepo.EXPECT().FindByProductIDs(gomock.Any(), []uint64{1}).Return(nil, nil).Times(1)
		reservationRepo.EXPECT().CreateBatch(gomock.Any(), gomock.Any()).Times(1).DoAndReturn(
			func(ctx context.Context, reservations []*model.StockReservation) ([]*model.StockReservation, error) {
				return reservations, nil
			},
		)

		service := NewReservationService(reservationRepo, productRepo)

		reservations, err := service.ReserveItems(context.Background(), 100, []dto.ReservationDTO{{ProductID: 1, Quantity: 4}})

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if len(reservations) != 1 {
			t.Fatalf("got %d reservations, want 1", len(reservations))
		}
		if reservations[0].Quantity() != 4 || reservations[0].Holder() != model.ReservationHolderBuyer {
			t.Errorf("unexpected reservation: %+v", reservations[0])
		}
	})

	t.Run("fails when requested quantity exceeds available stock", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		product := testProduct(t, 1, 3)
		productRepo := mocks.NewMockProductRepository(ctrl)
		reservationRepo := mocks.NewMockReservationRepository(ctrl)

		productRepo.EXPECT().GetByIDs(gomock.Any(), []uint64{1}).Return([]*model.Product{product}, nil).Times(1)
		reservationRepo.EXPECT().FindByProductIDs(gomock.Any(), []uint64{1}).Return(nil, nil).Times(1)
		reservationRepo.EXPECT().CreateBatch(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, productRepo)

		_, err := service.ReserveItems(context.Background(), 100, []dto.ReservationDTO{{ProductID: 1, Quantity: 4}})

		if !errors.Is(err, model.ErrInsufficientStock) {
			t.Errorf("err = %v, want %v", err, model.ErrInsufficientStock)
		}
	})

	t.Run("propagates an error from fetching products", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		wantErr := errors.New("db down")
		productRepo := mocks.NewMockProductRepository(ctrl)
		reservationRepo := mocks.NewMockReservationRepository(ctrl)

		productRepo.EXPECT().GetByIDs(gomock.Any(), []uint64{1}).Return(nil, wantErr).Times(1)
		reservationRepo.EXPECT().FindByProductIDs(gomock.Any(), gomock.Any()).Times(0)
		reservationRepo.EXPECT().CreateBatch(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, productRepo)

		_, err := service.ReserveItems(context.Background(), 100, []dto.ReservationDTO{{ProductID: 1, Quantity: 1}})

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("propagates an error from fetching existing reservations", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		wantErr := errors.New("db down")
		product := testProduct(t, 1, 10)
		productRepo := mocks.NewMockProductRepository(ctrl)
		reservationRepo := mocks.NewMockReservationRepository(ctrl)

		productRepo.EXPECT().GetByIDs(gomock.Any(), []uint64{1}).Return([]*model.Product{product}, nil).Times(1)
		reservationRepo.EXPECT().FindByProductIDs(gomock.Any(), []uint64{1}).Return(nil, wantErr).Times(1)
		reservationRepo.EXPECT().CreateBatch(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, productRepo)

		_, err := service.ReserveItems(context.Background(), 100, []dto.ReservationDTO{{ProductID: 1, Quantity: 1}})

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}

func TestReservationServiceImpl_Cancel(t *testing.T) {
	t.Run("deletes every reservation held for the order", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		r1 := testReservation(t, 1, 10, 100, 2, model.ReservationHolderBuyer)
		r2 := testReservation(t, 2, 11, 100, 3, model.ReservationHolderBuyer)
		reservationRepo := mocks.NewMockReservationRepository(ctrl)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), uint64(100)).Return([]*model.StockReservation{r1, r2}, nil).Times(1)
		reservationRepo.EXPECT().DeleteByIDs(gomock.Any(), []uint64{1, 2}).Return(nil).Times(1)

		service := NewReservationService(reservationRepo, mocks.NewMockProductRepository(ctrl))

		if err := service.Cancel(context.Background(), 100); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
	})

	t.Run("propagates an error from fetching reservations", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		wantErr := errors.New("db down")
		reservationRepo := mocks.NewMockReservationRepository(ctrl)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), uint64(100)).Return(nil, wantErr).Times(1)
		reservationRepo.EXPECT().DeleteByIDs(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, mocks.NewMockProductRepository(ctrl))

		err := service.Cancel(context.Background(), 100)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}

func TestReservationServiceImpl_HoldByCourier(t *testing.T) {
	t.Run("moves every reservation for the order to the courier", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		r1 := testReservation(t, 1, 10, 100, 2, model.ReservationHolderBuyer)
		r2 := testReservation(t, 2, 11, 100, 3, model.ReservationHolderBuyer)
		reservationRepo := mocks.NewMockReservationRepository(ctrl)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), uint64(100)).Return([]*model.StockReservation{r1, r2}, nil).Times(1)

		var updated []*model.StockReservation
		reservationRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(2).DoAndReturn(
			func(ctx context.Context, reservation *model.StockReservation) (*model.StockReservation, error) {
				updated = append(updated, reservation)
				return reservation, nil
			},
		)

		service := NewReservationService(reservationRepo, mocks.NewMockProductRepository(ctrl))

		if err := service.HoldByCourier(context.Background(), 100); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if len(updated) != 2 {
			t.Fatalf("Update called %d times, want 2", len(updated))
		}
		for _, r := range updated {
			if r.Holder() != model.ReservationHolderCourier {
				t.Errorf("Holder() = %v, want %v", r.Holder(), model.ReservationHolderCourier)
			}
		}
	})

	t.Run("propagates an error from fetching reservations", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		wantErr := errors.New("db down")
		reservationRepo := mocks.NewMockReservationRepository(ctrl)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), uint64(100)).Return(nil, wantErr).Times(1)
		reservationRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, mocks.NewMockProductRepository(ctrl))

		err := service.HoldByCourier(context.Background(), 100)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("propagates an error updating a reservation's holder", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		wantErr := errors.New("db down")
		r1 := testReservation(t, 1, 10, 100, 2, model.ReservationHolderBuyer)
		reservationRepo := mocks.NewMockReservationRepository(ctrl)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), uint64(100)).Return([]*model.StockReservation{r1}, nil).Times(1)
		reservationRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Return(nil, wantErr).Times(1)

		service := NewReservationService(reservationRepo, mocks.NewMockProductRepository(ctrl))

		err := service.HoldByCourier(context.Background(), 100)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}

func TestReservationServiceImpl_Fulfill(t *testing.T) {
	t.Run("removes on-hand stock and deletes the fulfilled reservations", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		r1 := testReservation(t, 1, 10, 100, 2, model.ReservationHolderCourier)
		product := testProduct(t, 10, 10)
		reservationRepo := mocks.NewMockReservationRepository(ctrl)
		productRepo := mocks.NewMockProductRepository(ctrl)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), uint64(100)).Return([]*model.StockReservation{r1}, nil).Times(1)
		productRepo.EXPECT().GetByID(gomock.Any(), uint64(10)).Return(product, nil).Times(1)

		var updatedProduct *model.Product
		productRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(1).DoAndReturn(
			func(ctx context.Context, p *model.Product) (*model.Product, error) {
				updatedProduct = p
				return p, nil
			},
		)
		reservationRepo.EXPECT().DeleteByIDs(gomock.Any(), []uint64{1}).Return(nil).Times(1)

		service := NewReservationService(reservationRepo, productRepo)

		if err := service.Fulfill(context.Background(), 100); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if updatedProduct == nil || updatedProduct.Quantity() != 8 {
			t.Fatalf("unexpected product update: %+v", updatedProduct)
		}
	})

	t.Run("propagates an error from fetching reservations", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		wantErr := errors.New("db down")
		reservationRepo := mocks.NewMockReservationRepository(ctrl)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), uint64(100)).Return(nil, wantErr).Times(1)
		reservationRepo.EXPECT().DeleteByIDs(gomock.Any(), gomock.Any()).Times(0)
		productRepo := mocks.NewMockProductRepository(ctrl)
		productRepo.EXPECT().GetByID(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, productRepo)

		err := service.Fulfill(context.Background(), 100)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("propagates an error fetching the product and does not delete the reservation", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		wantErr := errors.New("db down")
		r1 := testReservation(t, 1, 10, 100, 2, model.ReservationHolderCourier)
		reservationRepo := mocks.NewMockReservationRepository(ctrl)
		productRepo := mocks.NewMockProductRepository(ctrl)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), uint64(100)).Return([]*model.StockReservation{r1}, nil).Times(1)
		productRepo.EXPECT().GetByID(gomock.Any(), uint64(10)).Return(nil, wantErr).Times(1)
		productRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		reservationRepo.EXPECT().DeleteByIDs(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, productRepo)

		err := service.Fulfill(context.Background(), 100)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}

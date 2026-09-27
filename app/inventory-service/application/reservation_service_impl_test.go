package application

import (
	"context"
	"errors"
	"testing"

	"go.uber.org/mock/gomock"

	"inventoryservice/application/dto"
	idempotencydto "inventoryservice/application/port/out/idempotency/dto"
	idempotencymocks "inventoryservice/application/port/out/idempotency/mocks"
	"inventoryservice/application/port/out/repository/mocks"
	"inventoryservice/domain/model"
	"inventoryservice/domain/values"

	"github.com/google/uuid"
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
		productID := uint64(1)
		quantity := uint(4)
		product := testProduct(t, productID, 10)
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))

		productRepo.EXPECT().GetByIDs(gomock.Any(), []uint64{productID}).Return([]*model.Product{product}, nil).Times(1)
		reservationRepo.EXPECT().FindByProductIDs(gomock.Any(), []uint64{productID}).Return(nil, nil).Times(1)
		reservationRepo.EXPECT().CreateBatch(gomock.Any(), gomock.Any()).Times(1).DoAndReturn(
			func(ctx context.Context, reservations []*model.StockReservation) ([]*model.StockReservation, error) {
				return reservations, nil
			},
		)

		service := NewReservationService(reservationRepo, productRepo, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		reservations, err := service.ReserveItems(context.Background(), 100, []dto.ReservationDTO{{ProductID: productID, Quantity: quantity}})

		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if len(reservations) != 1 {
			t.Fatalf("got %d reservations, want 1", len(reservations))
		}
		if reservations[0].Quantity() != quantity || reservations[0].Holder() != model.ReservationHolderBuyer {
			t.Errorf("unexpected reservation: %+v", reservations[0])
		}
	})

	t.Run("fails when requested quantity exceeds available stock", func(t *testing.T) {
		productID := uint64(1)
		product := testProduct(t, productID, 3)
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))

		productRepo.EXPECT().GetByIDs(gomock.Any(), []uint64{productID}).Return([]*model.Product{product}, nil).Times(1)
		reservationRepo.EXPECT().FindByProductIDs(gomock.Any(), []uint64{productID}).Return(nil, nil).Times(1)
		reservationRepo.EXPECT().CreateBatch(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, productRepo, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		_, err := service.ReserveItems(context.Background(), 100, []dto.ReservationDTO{{ProductID: productID, Quantity: 4}})

		if !errors.Is(err, model.ErrInsufficientStock) {
			t.Errorf("err = %v, want %v", err, model.ErrInsufficientStock)
		}
	})

	t.Run("propagates an error from fetching products", func(t *testing.T) {
		productID := uint64(1)
		wantErr := errors.New("db down")
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))

		productRepo.EXPECT().GetByIDs(gomock.Any(), []uint64{productID}).Return(nil, wantErr).Times(1)
		reservationRepo.EXPECT().FindByProductIDs(gomock.Any(), gomock.Any()).Times(0)
		reservationRepo.EXPECT().CreateBatch(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, productRepo, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		_, err := service.ReserveItems(context.Background(), 100, []dto.ReservationDTO{{ProductID: productID, Quantity: 1}})

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("propagates an error from fetching existing reservations", func(t *testing.T) {
		productID := uint64(1)
		wantErr := errors.New("db down")
		product := testProduct(t, productID, 10)
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))

		productRepo.EXPECT().GetByIDs(gomock.Any(), []uint64{productID}).Return([]*model.Product{product}, nil).Times(1)
		reservationRepo.EXPECT().FindByProductIDs(gomock.Any(), []uint64{productID}).Return(nil, wantErr).Times(1)
		reservationRepo.EXPECT().CreateBatch(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, productRepo, idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t)), noopTxExecutor{})

		_, err := service.ReserveItems(context.Background(), 100, []dto.ReservationDTO{{ProductID: productID, Quantity: 1}})

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}

func TestReservationServiceImpl_Cancel(t *testing.T) {
	msg := idempotencydto.KafkaMessageMetadata{MessageID: uuid.New(), CorrelationID: "corr-1", MessageType: "CancelReservation"}

	t.Run("deletes every reservation held for the order", func(t *testing.T) {
		orderID := uint64(100)
		r1 := testReservation(t, 1, 10, orderID, 2, model.ReservationHolderBuyer)
		r2 := testReservation(t, 2, 11, orderID, 3, model.ReservationHolderBuyer)
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), orderID).Return([]*model.StockReservation{r1, r2}, nil).Times(1)
		reservationRepo.EXPECT().DeleteByIDs(gomock.Any(), []uint64{1, 2}).Return(nil).Times(1)

		service := NewReservationService(reservationRepo, mocks.NewMockProductRepository(gomock.NewController(t)), processedMessageStore, noopTxExecutor{})

		if err := service.Cancel(context.Background(), msg, orderID); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
	})

	t.Run("skips reprocessing an already-processed message without error", func(t *testing.T) {
		orderID := uint64(100)
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(true, nil).Times(1)
		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), gomock.Any()).Times(0)
		reservationRepo.EXPECT().DeleteByIDs(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, mocks.NewMockProductRepository(gomock.NewController(t)), processedMessageStore, noopTxExecutor{})

		if err := service.Cancel(context.Background(), msg, orderID); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
	})

	t.Run("propagates an error from fetching reservations", func(t *testing.T) {
		orderID := uint64(100)
		wantErr := errors.New("db down")
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), orderID).Return(nil, wantErr).Times(1)
		reservationRepo.EXPECT().DeleteByIDs(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, mocks.NewMockProductRepository(gomock.NewController(t)), processedMessageStore, noopTxExecutor{})

		err := service.Cancel(context.Background(), msg, orderID)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}

func TestReservationServiceImpl_HoldByCourier(t *testing.T) {
	msg := idempotencydto.KafkaMessageMetadata{MessageID: uuid.New(), CorrelationID: "corr-1", MessageType: "HoldReservation"}

	t.Run("moves every reservation for the order to the courier", func(t *testing.T) {
		orderID := uint64(100)
		r1 := testReservation(t, 1, 10, orderID, 2, model.ReservationHolderBuyer)
		r2 := testReservation(t, 2, 11, orderID, 3, model.ReservationHolderBuyer)
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), orderID).Return([]*model.StockReservation{r1, r2}, nil).Times(1)

		var updated []*model.StockReservation
		reservationRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(2).DoAndReturn(
			func(ctx context.Context, reservation *model.StockReservation) (*model.StockReservation, error) {
				updated = append(updated, reservation)
				return reservation, nil
			},
		)

		service := NewReservationService(reservationRepo, mocks.NewMockProductRepository(gomock.NewController(t)), processedMessageStore, noopTxExecutor{})

		if err := service.HoldByCourier(context.Background(), msg, orderID); err != nil {
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

	t.Run("skips reprocessing an already-processed message without error", func(t *testing.T) {
		orderID := uint64(100)
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(true, nil).Times(1)
		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), gomock.Any()).Times(0)
		reservationRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, mocks.NewMockProductRepository(gomock.NewController(t)), processedMessageStore, noopTxExecutor{})

		if err := service.HoldByCourier(context.Background(), msg, orderID); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
	})

	t.Run("propagates an error from fetching reservations", func(t *testing.T) {
		orderID := uint64(100)
		wantErr := errors.New("db down")
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), orderID).Return(nil, wantErr).Times(1)
		reservationRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, mocks.NewMockProductRepository(gomock.NewController(t)), processedMessageStore, noopTxExecutor{})

		err := service.HoldByCourier(context.Background(), msg, orderID)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("propagates an error updating a reservation's holder", func(t *testing.T) {
		orderID := uint64(100)
		wantErr := errors.New("db down")
		r1 := testReservation(t, 1, 10, orderID, 2, model.ReservationHolderBuyer)
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), orderID).Return([]*model.StockReservation{r1}, nil).Times(1)
		reservationRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Return(nil, wantErr).Times(1)

		service := NewReservationService(reservationRepo, mocks.NewMockProductRepository(gomock.NewController(t)), processedMessageStore, noopTxExecutor{})

		err := service.HoldByCourier(context.Background(), msg, orderID)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}

func TestReservationServiceImpl_Fulfill(t *testing.T) {
	msg := idempotencydto.KafkaMessageMetadata{MessageID: uuid.New(), CorrelationID: "corr-1", MessageType: "FulfillReservation"}

	t.Run("removes on-hand stock and deletes the fulfilled reservations", func(t *testing.T) {
		orderID := uint64(100)
		productID := uint64(10)
		r1 := testReservation(t, 1, productID, orderID, 2, model.ReservationHolderCourier)
		product := testProduct(t, productID, 10)
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), orderID).Return([]*model.StockReservation{r1}, nil).Times(1)
		productRepo.EXPECT().GetByID(gomock.Any(), productID).Return(product, nil).Times(1)

		var updatedProduct *model.Product
		productRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(1).DoAndReturn(
			func(ctx context.Context, p *model.Product) (*model.Product, error) {
				updatedProduct = p
				return p, nil
			},
		)
		reservationRepo.EXPECT().DeleteByIDs(gomock.Any(), []uint64{1}).Return(nil).Times(1)

		service := NewReservationService(reservationRepo, productRepo, processedMessageStore, noopTxExecutor{})

		if err := service.Fulfill(context.Background(), msg, orderID); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if updatedProduct == nil || updatedProduct.Quantity() != 8 {
			t.Fatalf("unexpected product update: %+v", updatedProduct)
		}
	})

	t.Run("skips reprocessing an already-processed message without error", func(t *testing.T) {
		orderID := uint64(100)
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(true, nil).Times(1)
		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), gomock.Any()).Times(0)
		reservationRepo.EXPECT().DeleteByIDs(gomock.Any(), gomock.Any()).Times(0)
		productRepo.EXPECT().GetByID(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, productRepo, processedMessageStore, noopTxExecutor{})

		if err := service.Fulfill(context.Background(), msg, orderID); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
	})

	t.Run("propagates an error from fetching reservations", func(t *testing.T) {
		orderID := uint64(100)
		wantErr := errors.New("db down")
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), orderID).Return(nil, wantErr).Times(1)
		reservationRepo.EXPECT().DeleteByIDs(gomock.Any(), gomock.Any()).Times(0)
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		productRepo.EXPECT().GetByID(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, productRepo, processedMessageStore, noopTxExecutor{})

		err := service.Fulfill(context.Background(), msg, orderID)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})

	t.Run("propagates an error fetching the product and does not delete the reservation", func(t *testing.T) {
		orderID := uint64(100)
		productID := uint64(10)
		wantErr := errors.New("db down")
		r1 := testReservation(t, 1, productID, orderID, 2, model.ReservationHolderCourier)
		reservationRepo := mocks.NewMockReservationRepository(gomock.NewController(t))
		productRepo := mocks.NewMockProductRepository(gomock.NewController(t))
		processedMessageStore := idempotencymocks.NewMockProcessedKafkaMessageStore(gomock.NewController(t))
		processedMessageStore.EXPECT().MarkProcessed(gomock.Any(), msg).Return(false, nil).Times(1)

		reservationRepo.EXPECT().GetByOrderID(gomock.Any(), orderID).Return([]*model.StockReservation{r1}, nil).Times(1)
		productRepo.EXPECT().GetByID(gomock.Any(), productID).Return(nil, wantErr).Times(1)
		productRepo.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		reservationRepo.EXPECT().DeleteByIDs(gomock.Any(), gomock.Any()).Times(0)

		service := NewReservationService(reservationRepo, productRepo, processedMessageStore, noopTxExecutor{})

		err := service.Fulfill(context.Background(), msg, orderID)

		if !errors.Is(err, wantErr) {
			t.Errorf("err = %v, want %v", err, wantErr)
		}
	})
}

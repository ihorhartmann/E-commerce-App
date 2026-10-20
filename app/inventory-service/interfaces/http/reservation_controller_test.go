package http

import (
	"bytes"
	"fmt"
	"net/http/httptest"
	"testing"

	"github.com/gofiber/fiber/v2"
	"go.uber.org/mock/gomock"

	"inventoryservice/application/dto"
	"inventoryservice/application/port/in/mocks"
	"inventoryservice/domain/apperror"
	"inventoryservice/domain/model"
	reservationdto "inventoryservice/interfaces/http/dto/reservation"
	"inventoryservice/interfaces/http/httperror"
	"inventoryservice/interfaces/http/middleware"
)

func newReservationTestApp(reservationService *mocks.MockReservationService) *fiber.App {
	app := fiber.New(fiber.Config{ErrorHandler: middleware.HandleMiddlewareError})
	app.Use(middleware.HandleControllerError)
	NewReservationController(reservationService).RegisterRoutes(app)
	return app
}

func TestReservationController_CreateReservation(t *testing.T) {
	t.Run("returns 201 with the name, quantity and price of every reserved line", func(t *testing.T) {
		orderID := uint64(100)
		productID := uint64(1)
		quantity := uint(2)
		product := testProduct(t, productID, 10, quantity)
		reservationService := mocks.NewMockReservationService(gomock.NewController(t))
		reservationDTOs := []dto.ReservationDTO{{ProductID: productID, Quantity: quantity}}
		reservationService.EXPECT().
			ReserveItems(gomock.Any(), orderID, reservationDTOs).
			Return([]dto.ReservedItemDTO{{Product: product, Quantity: quantity}}, nil).
			Times(1)
		app := newReservationTestApp(reservationService)

		requestBody := fmt.Sprintf(`{"orderId": %d, "items": [{"productId": %d, "quantity": %d}]}`, orderID, productID, quantity)
		req := httptest.NewRequest("POST", "/reservations/", bytes.NewBufferString(requestBody))
		req.Header.Set("Content-Type", "application/json")

		resp, err := app.Test(req)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if resp.StatusCode != 201 {
			t.Errorf("status = %d, want 201", resp.StatusCode)
		}
		items, ok := decodeBody(t, resp.Body)["items"].([]any)
		if !ok || len(items) != 1 {
			t.Fatalf("items = %v, want one reserved product", items)
		}
		item := items[0].(map[string]any)
		if item["productId"] != float64(productID) {
			t.Errorf("items[0].productId = %v, want %d", item["productId"], productID)
		}
		if item["name"] != product.Name() {
			t.Errorf("items[0].name = %v, want %q", item["name"], product.Name())
		}
		if item["quantity"] != float64(quantity) {
			t.Errorf("items[0].quantity = %v, want %d", item["quantity"], quantity)
		}
		price, ok := item["price"].(map[string]any)
		if !ok || price["amount"] != 10.0 || price["currency"] != "EUR" {
			t.Errorf("items[0].price = %v, want 10 EUR", item["price"])
		}
	})

	t.Run("returns 400 when the request body is invalid", func(t *testing.T) {
		reservationService := mocks.NewMockReservationService(gomock.NewController(t))
		reservationService.EXPECT().ReserveItems(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		app := newReservationTestApp(reservationService)

		req := httptest.NewRequest("POST", "/reservations/", bytes.NewBufferString("not json"))
		req.Header.Set("Content-Type", "application/json")

		resp, err := app.Test(req)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if resp.StatusCode != 400 {
			t.Errorf("status = %d, want 400", resp.StatusCode)
		}
		body := decodeBody(t, resp.Body)
		if body["code"] != httperror.ErrMalformedRequestBody.Code() {
			t.Errorf("code = %v, want %q", body["code"], httperror.ErrMalformedRequestBody.Code())
		}
	})

	t.Run("returns 400 when the request fails validation", func(t *testing.T) {
		reservationService := mocks.NewMockReservationService(gomock.NewController(t))
		reservationService.EXPECT().ReserveItems(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		app := newReservationTestApp(reservationService)

		requestBody := `{"orderId": 0, "items": []}`
		req := httptest.NewRequest("POST", "/reservations/", bytes.NewBufferString(requestBody))
		req.Header.Set("Content-Type", "application/json")

		resp, err := app.Test(req)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if resp.StatusCode != 400 {
			t.Errorf("status = %d, want 400", resp.StatusCode)
		}
		body := decodeBody(t, resp.Body)
		if body["code"] != reservationdto.ErrInvalidReservationRequest.Code() {
			t.Errorf("code = %v, want %q", body["code"], reservationdto.ErrInvalidReservationRequest.Code())
		}
	})

	t.Run("returns 409 when there is insufficient stock", func(t *testing.T) {
		orderID := uint64(100)
		productID := uint64(1)
		quantity := uint(2)
		reservationService := mocks.NewMockReservationService(gomock.NewController(t))
		reservationDTOs := []dto.ReservationDTO{{ProductID: productID, Quantity: quantity}}
		reservationService.EXPECT().
			ReserveItems(gomock.Any(), orderID, reservationDTOs).
			Return(nil, model.ErrInsufficientStock).
			Times(1)
		app := newReservationTestApp(reservationService)

		requestBody := fmt.Sprintf(`{"orderId": %d, "items": [{"productId": %d, "quantity": %d}]}`, orderID, productID, quantity)
		req := httptest.NewRequest("POST", "/reservations/", bytes.NewBufferString(requestBody))
		req.Header.Set("Content-Type", "application/json")

		resp, err := app.Test(req)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if resp.StatusCode != 409 {
			t.Errorf("status = %d, want 409", resp.StatusCode)
		}
		body := decodeBody(t, resp.Body)
		if body["code"] != model.ErrInsufficientStock.Code() {
			t.Errorf("code = %v, want %q", body["code"], model.ErrInsufficientStock.Code())
		}
	})

	t.Run("returns 500 when an unexpected error occurs", func(t *testing.T) {
		orderID := uint64(100)
		productID := uint64(1)
		quantity := uint(2)
		reservationService := mocks.NewMockReservationService(gomock.NewController(t))
		reservationDTOs := []dto.ReservationDTO{{ProductID: productID, Quantity: quantity}}
		reservationService.EXPECT().
			ReserveItems(gomock.Any(), orderID, reservationDTOs).
			Return(nil, fmt.Errorf("db down")).
			Times(1)
		app := newReservationTestApp(reservationService)

		requestBody := fmt.Sprintf(`{"orderId": %d, "items": [{"productId": %d, "quantity": %d}]}`, orderID, productID, quantity)
		req := httptest.NewRequest("POST", "/reservations/", bytes.NewBufferString(requestBody))
		req.Header.Set("Content-Type", "application/json")

		resp, err := app.Test(req)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if resp.StatusCode != 500 {
			t.Errorf("status = %d, want 500", resp.StatusCode)
		}
		body := decodeBody(t, resp.Body)
		if body["code"] != apperror.ErrInternal.Code() {
			t.Errorf("code = %v, want %q", body["code"], apperror.ErrInternal.Code())
		}
	})
}

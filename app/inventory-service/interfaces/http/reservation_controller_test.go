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
	"inventoryservice/domain"
	"inventoryservice/domain/model"
	httperrors "inventoryservice/interfaces/http/errors"
	"inventoryservice/interfaces/http/middleware"
)

func newReservationTestApp(reservationService *mocks.MockReservationService) *fiber.App {
	app := fiber.New(fiber.Config{ErrorHandler: middleware.HandleError})
	NewReservationController(reservationService).RegisterRoutes(app)
	return app
}

func TestReservationController_CreateReservation(t *testing.T) {
	t.Run("returns 201 with the price of every reserved product", func(t *testing.T) {
		orderID := uint64(100)
		productID := uint64(1)
		quantity := uint(2)
		reservationService := mocks.NewMockReservationService(gomock.NewController(t))
		reservationDTOs := []dto.ReservationDTO{{ProductID: productID, Quantity: quantity}}
		reservationService.EXPECT().
			ReserveItems(gomock.Any(), orderID, reservationDTOs).
			Return([]*model.Product{testProduct(t, productID, 10, quantity)}, nil).
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
		if body["error"] != httperrors.ErrInvalidRequestBody.Error() {
			t.Errorf("error = %v, want %q", body["error"], httperrors.ErrInvalidRequestBody.Error())
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
		if body["error"] != domain.ErrInvalidInput.Error() {
			t.Errorf("error = %v, want %q", body["error"], domain.ErrInvalidInput.Error())
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
		if body["error"] != model.ErrInsufficientStock.Error() {
			t.Errorf("error = %v, want %q", body["error"], model.ErrInsufficientStock.Error())
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
		if body["error"] != middleware.InternalServerErrorMessage {
			t.Errorf("error = %v, want %q", body["error"], middleware.InternalServerErrorMessage)
		}
	})
}

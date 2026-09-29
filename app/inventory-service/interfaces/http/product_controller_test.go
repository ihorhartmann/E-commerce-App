package http

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http/httptest"
	"testing"

	"github.com/gofiber/fiber/v2"
	"go.uber.org/mock/gomock"

	"inventoryservice/application/dto"
	"inventoryservice/application/port/in/mocks"
	"inventoryservice/application/port/out/repository"
	"inventoryservice/domain"
	"inventoryservice/domain/model"
	"inventoryservice/domain/values"
	httperrors "inventoryservice/interfaces/http/errors"
	"inventoryservice/interfaces/http/middleware"
)

func testMoney(t *testing.T) values.Money {
	t.Helper()
	money, err := values.NewMoney(10.0, values.USD)
	if err != nil {
		t.Fatalf("unexpected error building test money: %v", err)
	}
	return money
}

func testProduct(t *testing.T, id uint64, quantity, reserved uint) *model.Product {
	t.Helper()
	product, err := model.NewExistingProduct(id, "Widget", "a widget", testMoney(t), quantity, reserved, 1)
	if err != nil {
		t.Fatalf("unexpected error building test product: %v", err)
	}
	return product
}

func newProductTestApp(productService *mocks.MockProductService) *fiber.App {
	app := fiber.New(fiber.Config{ErrorHandler: middleware.HandleError})
	NewProductController(productService).RegisterRoutes(app)
	return app
}

func decodeBody(t *testing.T, body io.ReadCloser) map[string]any {
	t.Helper()
	defer func(body io.ReadCloser) {
		err := body.Close()
		if err != nil {
			t.Errorf("error closing body: %v", err)
		}
	}(body)
	var decoded map[string]any
	if err := json.NewDecoder(body).Decode(&decoded); err != nil {
		t.Fatalf("failed to decode response body: %v", err)
	}
	return decoded
}

func TestProductController_CreateProduct(t *testing.T) {
	t.Run("returns 201 with the created product", func(t *testing.T) {
		quantity := uint(10)
		productService := mocks.NewMockProductService(gomock.NewController(t))
		createdProduct := testProduct(t, 1, quantity, 0)
		productService.EXPECT().Create(gomock.Any(), gomock.Any()).Return(createdProduct, nil).Times(1)
		app := newProductTestApp(productService)

		requestBody := `{"name": "Widget", "description": "a widget", "price": {"amount": 10.0, "currency": "USD"}, "quantity": 10}`
		req := httptest.NewRequest("POST", "/products/", bytes.NewBufferString(requestBody))
		req.Header.Set("Content-Type", "application/json")

		resp, err := app.Test(req)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if resp.StatusCode != 201 {
			t.Errorf("status = %d, want 201", resp.StatusCode)
		}
		body := decodeBody(t, resp.Body)
		if body["id"] != float64(1) {
			t.Errorf("id = %v, want 1", body["id"])
		}
		if body["name"] != "Widget" {
			t.Errorf("name = %v, want %q", body["name"], "Widget")
		}
		if body["quantity"] != float64(quantity) {
			t.Errorf("quantity = %v, want %d", body["quantity"], quantity)
		}
	})

	t.Run("returns 400 when the request body is invalid", func(t *testing.T) {
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productService.EXPECT().Create(gomock.Any(), gomock.Any()).Times(0)
		app := newProductTestApp(productService)

		req := httptest.NewRequest("POST", "/products/", bytes.NewBufferString("not json"))
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

	t.Run("returns 400 when the product fails domain validation", func(t *testing.T) {
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productService.EXPECT().Create(gomock.Any(), gomock.Any()).Times(0)
		app := newProductTestApp(productService)

		requestBody := `{"name": "", "description": "a widget", "price": {"amount": 10.0, "currency": "USD"}, "quantity": 10}`
		req := httptest.NewRequest("POST", "/products/", bytes.NewBufferString(requestBody))
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

	t.Run("returns 500 when an unexpected error occurs", func(t *testing.T) {
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productService.EXPECT().Create(gomock.Any(), gomock.Any()).Return(nil, errors.New("db down")).Times(1)
		app := newProductTestApp(productService)

		requestBody := `{"name": "Widget", "description": "a widget", "price": {"amount": 10.0, "currency": "USD"}, "quantity": 10}`
		req := httptest.NewRequest("POST", "/products/", bytes.NewBufferString(requestBody))
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

func TestProductController_UpdateProduct(t *testing.T) {
	t.Run("returns 200 with the updated product", func(t *testing.T) {
		id := uint64(1)
		quantity := uint(20)
		productService := mocks.NewMockProductService(gomock.NewController(t))
		updatedProduct := testProduct(t, id, quantity, 0)
		wantUpdate := dto.UpdateProductDTO{ID: id, Name: "Widget", Description: "a widget", Price: testMoney(t), Quantity: quantity}
		productService.EXPECT().Update(gomock.Any(), wantUpdate).Return(updatedProduct, nil).Times(1)
		app := newProductTestApp(productService)

		requestBody := `{"name": "Widget", "description": "a widget", "price": {"amount": 10.0, "currency": "USD"}, "quantity": 20}`
		req := httptest.NewRequest("PUT", "/products/1", bytes.NewBufferString(requestBody))
		req.Header.Set("Content-Type", "application/json")

		resp, err := app.Test(req)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if resp.StatusCode != 200 {
			t.Errorf("status = %d, want 200", resp.StatusCode)
		}
		body := decodeBody(t, resp.Body)
		if body["id"] != float64(id) {
			t.Errorf("id = %v, want %d", body["id"], id)
		}
		if body["quantity"] != float64(quantity) {
			t.Errorf("quantity = %v, want %d", body["quantity"], quantity)
		}
	})

	t.Run("returns 400 when the id param is invalid", func(t *testing.T) {
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productService.EXPECT().Update(gomock.Any(), gomock.Any()).Times(0)
		app := newProductTestApp(productService)

		requestBody := `{"name": "Widget", "description": "a widget", "price": {"amount": 10.0, "currency": "USD"}, "quantity": 20}`
		req := httptest.NewRequest("PUT", "/products/abc", bytes.NewBufferString(requestBody))
		req.Header.Set("Content-Type", "application/json")

		resp, err := app.Test(req)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if resp.StatusCode != 400 {
			t.Errorf("status = %d, want 400", resp.StatusCode)
		}
		body := decodeBody(t, resp.Body)
		if body["error"] != httperrors.ErrInvalidProductID.Error() {
			t.Errorf("error = %v, want %q", body["error"], httperrors.ErrInvalidProductID.Error())
		}
	})

	t.Run("returns 409 when the new quantity is below what is reserved", func(t *testing.T) {
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productService.EXPECT().Update(gomock.Any(), gomock.Any()).Return(nil, model.ErrQuantityBelowReserved).Times(1)
		app := newProductTestApp(productService)

		requestBody := `{"name": "Widget", "description": "a widget", "price": {"amount": 10.0, "currency": "USD"}, "quantity": 1}`
		req := httptest.NewRequest("PUT", "/products/1", bytes.NewBufferString(requestBody))
		req.Header.Set("Content-Type", "application/json")

		resp, err := app.Test(req)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if resp.StatusCode != 409 {
			t.Errorf("status = %d, want 409", resp.StatusCode)
		}
		body := decodeBody(t, resp.Body)
		if body["error"] != model.ErrQuantityBelowReserved.Error() {
			t.Errorf("error = %v, want %q", body["error"], model.ErrQuantityBelowReserved.Error())
		}
	})

	t.Run("returns 503 when the product was changed concurrently", func(t *testing.T) {
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productService.EXPECT().Update(gomock.Any(), gomock.Any()).Return(nil, repository.ErrConcurrentUpdate).Times(1)
		app := newProductTestApp(productService)

		requestBody := `{"name": "Widget", "description": "a widget", "price": {"amount": 10.0, "currency": "USD"}, "quantity": 20}`
		req := httptest.NewRequest("PUT", "/products/1", bytes.NewBufferString(requestBody))
		req.Header.Set("Content-Type", "application/json")

		resp, err := app.Test(req)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if resp.StatusCode != 503 {
			t.Errorf("status = %d, want 503", resp.StatusCode)
		}
		// like every 5xx, it only tells the client something went wrong, not what
		body := decodeBody(t, resp.Body)
		if body["error"] != middleware.InternalServerErrorMessage {
			t.Errorf("error = %v, want %q", body["error"], middleware.InternalServerErrorMessage)
		}
	})

	t.Run("returns 404 when the product is not found", func(t *testing.T) {
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productService.EXPECT().Update(gomock.Any(), gomock.Any()).Return(nil, repository.ErrProductNotFound).Times(1)
		app := newProductTestApp(productService)

		requestBody := `{"name": "Widget", "description": "a widget", "price": {"amount": 10.0, "currency": "USD"}, "quantity": 20}`
		req := httptest.NewRequest("PUT", "/products/1", bytes.NewBufferString(requestBody))
		req.Header.Set("Content-Type", "application/json")

		resp, err := app.Test(req)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if resp.StatusCode != 404 {
			t.Errorf("status = %d, want 404", resp.StatusCode)
		}
		body := decodeBody(t, resp.Body)
		if body["error"] != repository.ErrProductNotFound.Error() {
			t.Errorf("error = %v, want %q", body["error"], repository.ErrProductNotFound.Error())
		}
	})
}

func TestProductController_GetProduct(t *testing.T) {
	t.Run("returns 200 with the product and its reserved and available quantity", func(t *testing.T) {
		id := uint64(1)
		productService := mocks.NewMockProductService(gomock.NewController(t))
		product := testProduct(t, id, 10, 3)
		productService.EXPECT().Get(gomock.Any(), id).Return(product, nil).Times(1)
		app := newProductTestApp(productService)

		req := httptest.NewRequest("GET", "/products/1", nil)

		resp, err := app.Test(req)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if resp.StatusCode != 200 {
			t.Errorf("status = %d, want 200", resp.StatusCode)
		}
		body := decodeBody(t, resp.Body)
		if body["id"] != float64(id) {
			t.Errorf("id = %v, want %d", body["id"], id)
		}
		if body["name"] != "Widget" {
			t.Errorf("name = %v, want %q", body["name"], "Widget")
		}
		if body["reserved"] != float64(3) {
			t.Errorf("reserved = %v, want 3", body["reserved"])
		}
		if body["available"] != float64(7) {
			t.Errorf("available = %v, want 7", body["available"])
		}
	})

	t.Run("returns 404 when the product is not found", func(t *testing.T) {
		id := uint64(999)
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productService.EXPECT().Get(gomock.Any(), id).Return(nil, repository.ErrProductNotFound).Times(1)
		app := newProductTestApp(productService)

		req := httptest.NewRequest("GET", "/products/999", nil)

		resp, err := app.Test(req)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if resp.StatusCode != 404 {
			t.Errorf("status = %d, want 404", resp.StatusCode)
		}
		body := decodeBody(t, resp.Body)
		if body["error"] != repository.ErrProductNotFound.Error() {
			t.Errorf("error = %v, want %q", body["error"], repository.ErrProductNotFound.Error())
		}
	})
}

func TestProductController_DeleteProduct(t *testing.T) {
	t.Run("returns 204 when the product is deleted", func(t *testing.T) {
		id := uint64(1)
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productService.EXPECT().Delete(gomock.Any(), id).Return(nil).Times(1)
		app := newProductTestApp(productService)

		req := httptest.NewRequest("DELETE", "/products/1", nil)

		resp, err := app.Test(req)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if resp.StatusCode != 204 {
			t.Errorf("status = %d, want 204", resp.StatusCode)
		}
	})

	t.Run("returns 404 when the product is not found", func(t *testing.T) {
		id := uint64(999)
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productService.EXPECT().Delete(gomock.Any(), id).Return(repository.ErrProductNotFound).Times(1)
		app := newProductTestApp(productService)

		req := httptest.NewRequest("DELETE", "/products/999", nil)

		resp, err := app.Test(req)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if resp.StatusCode != 404 {
			t.Errorf("status = %d, want 404", resp.StatusCode)
		}
		body := decodeBody(t, resp.Body)
		if body["error"] != repository.ErrProductNotFound.Error() {
			t.Errorf("error = %v, want %q", body["error"], repository.ErrProductNotFound.Error())
		}
	})

	t.Run("returns 409 when some of the product's stock is reserved", func(t *testing.T) {
		id := uint64(1)
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productService.EXPECT().Delete(gomock.Any(), id).Return(model.ErrProductReserved).Times(1)
		app := newProductTestApp(productService)

		req := httptest.NewRequest("DELETE", "/products/1", nil)

		resp, err := app.Test(req)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if resp.StatusCode != 409 {
			t.Errorf("status = %d, want 409", resp.StatusCode)
		}
		body := decodeBody(t, resp.Body)
		if body["error"] != model.ErrProductReserved.Error() {
			t.Errorf("error = %v, want %q", body["error"], model.ErrProductReserved.Error())
		}
	})
}

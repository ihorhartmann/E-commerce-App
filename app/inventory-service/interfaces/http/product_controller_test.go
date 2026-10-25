package http

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gofiber/fiber/v2"
	"go.uber.org/mock/gomock"

	"inventoryservice/application"
	"inventoryservice/application/dto"
	"inventoryservice/application/port/in/mocks"
	"inventoryservice/application/port/out/repository"
	"inventoryservice/domain/apperror"
	"inventoryservice/domain/model"
	"inventoryservice/domain/values"
	"inventoryservice/interfaces/http/httperror"
	"inventoryservice/interfaces/http/middleware"

	"github.com/shopspring/decimal"
)

func testMoney(t *testing.T) values.Money {
	t.Helper()
	money, err := values.NewMoney(decimal.NewFromInt(10), values.EUR)
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
	app := fiber.New(fiber.Config{ErrorHandler: middleware.HandleMiddlewareError})
	app.Use(middleware.HandleControllerError)
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

		requestBody := `{"name": "Widget", "description": "a widget", "price": {"amount": 10.0, "currency": "EUR"}, "quantity": 10}`
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
		if body["code"] != httperror.ErrMalformedRequestBody.Code() {
			t.Errorf("code = %v, want %q", body["code"], httperror.ErrMalformedRequestBody.Code())
		}
	})

	t.Run("returns 400 when the product fails domain validation", func(t *testing.T) {
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productService.EXPECT().Create(gomock.Any(), gomock.Any()).Times(0)
		app := newProductTestApp(productService)

		requestBody := `{"name": "", "description": "a widget", "price": {"amount": 10.0, "currency": "EUR"}, "quantity": 10}`
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
		if body["code"] != model.ErrInvalidProductName.Code() {
			t.Errorf("code = %v, want %q", body["code"], model.ErrInvalidProductName.Code())
		}
	})

	t.Run("returns 400 when the price is not in EUR", func(t *testing.T) {
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productService.EXPECT().Create(gomock.Any(), gomock.Any()).Times(0)
		app := newProductTestApp(productService)

		requestBody := `{"name": "Widget", "description": "a widget", "price": {"amount": 10.0, "currency": "USD"}, "quantity": 10}`
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
		if body["code"] != model.ErrUnsupportedPriceCurrency.Code() {
			t.Errorf("code = %v, want %q", body["code"], model.ErrUnsupportedPriceCurrency.Code())
		}
	})

	t.Run("reads the price exactly as written and writes it back as a number", func(t *testing.T) {
		amount := "79.99"
		productService := mocks.NewMockProductService(gomock.NewController(t))
		var gotProduct *model.Product
		productService.EXPECT().Create(gomock.Any(), gomock.Any()).Times(1).DoAndReturn(
			func(ctx context.Context, product *model.Product) (*model.Product, error) {
				gotProduct = product
				return model.NewExistingProduct(1, product.Name(), product.Description(), product.Price(), product.Quantity(), 0, 0)
			},
		)
		app := newProductTestApp(productService)

		requestBody := `{"name": "Widget", "description": "a widget", "price": {"amount": ` + amount + `, "currency": "EUR"}, "quantity": 10}`
		req := httptest.NewRequest("POST", "/products/", bytes.NewBufferString(requestBody))
		req.Header.Set("Content-Type", "application/json")

		resp, err := app.Test(req)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if resp.StatusCode != 201 {
			t.Fatalf("status = %d, want 201", resp.StatusCode)
		}
		if gotAmount := gotProduct.Price().Amount().String(); gotAmount != amount {
			t.Errorf("price passed to the service = %s, want %s", gotAmount, amount)
		}
		rawBody, err := io.ReadAll(resp.Body)
		if err != nil {
			t.Fatalf("failed to read the response body: %v", err)
		}
		if wantPrice := `"amount":` + amount + `,`; !strings.Contains(string(rawBody), wantPrice) {
			t.Errorf("body = %s, want it to contain %s", rawBody, wantPrice)
		}
	})

	t.Run("returns 400 when the price has no amount", func(t *testing.T) {
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productService.EXPECT().Create(gomock.Any(), gomock.Any()).Times(0)
		app := newProductTestApp(productService)

		requestBody := `{"name": "Widget", "description": "a widget", "price": {"currency": "EUR"}, "quantity": 10}`
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
		if body["code"] != values.ErrInvalidMoneyAmount.Code() {
			t.Errorf("code = %v, want %q", body["code"], values.ErrInvalidMoneyAmount.Code())
		}
	})

	t.Run("returns 500 when an unexpected error occurs", func(t *testing.T) {
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productService.EXPECT().Create(gomock.Any(), gomock.Any()).Return(nil, errors.New("db down")).Times(1)
		app := newProductTestApp(productService)

		requestBody := `{"name": "Widget", "description": "a widget", "price": {"amount": 10.0, "currency": "EUR"}, "quantity": 10}`
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
		if body["code"] != apperror.ErrInternal.Code() {
			t.Errorf("code = %v, want %q", body["code"], apperror.ErrInternal.Code())
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

		requestBody := `{"name": "Widget", "description": "a widget", "price": {"amount": 10.0, "currency": "EUR"}, "quantity": 20}`
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

		requestBody := `{"name": "Widget", "description": "a widget", "price": {"amount": 10.0, "currency": "EUR"}, "quantity": 20}`
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
		if body["code"] != httperror.ErrInvalidPathParam.Code() {
			t.Errorf("code = %v, want %q", body["code"], httperror.ErrInvalidPathParam.Code())
		}
	})

	t.Run("returns 409 when the new quantity is below what is reserved", func(t *testing.T) {
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productService.EXPECT().Update(gomock.Any(), gomock.Any()).Return(nil, model.ErrQuantityBelowReserved).Times(1)
		app := newProductTestApp(productService)

		requestBody := `{"name": "Widget", "description": "a widget", "price": {"amount": 10.0, "currency": "EUR"}, "quantity": 1}`
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
		if body["code"] != model.ErrQuantityBelowReserved.Code() {
			t.Errorf("code = %v, want %q", body["code"], model.ErrQuantityBelowReserved.Code())
		}
	})

	t.Run("returns 503 when the product was changed concurrently", func(t *testing.T) {
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productService.EXPECT().Update(gomock.Any(), gomock.Any()).Return(nil, repository.ErrConcurrentUpdate).Times(1)
		app := newProductTestApp(productService)

		requestBody := `{"name": "Widget", "description": "a widget", "price": {"amount": 10.0, "currency": "EUR"}, "quantity": 20}`
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
		if body["code"] != repository.ErrConcurrentUpdate.Code() {
			t.Errorf("code = %v, want %q", body["code"], repository.ErrConcurrentUpdate.Code())
		}
	})

	t.Run("returns 404 when the product is not found", func(t *testing.T) {
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productService.EXPECT().Update(gomock.Any(), gomock.Any()).Return(nil, repository.ErrProductNotFound).Times(1)
		app := newProductTestApp(productService)

		requestBody := `{"name": "Widget", "description": "a widget", "price": {"amount": 10.0, "currency": "EUR"}, "quantity": 20}`
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
		if body["code"] != repository.ErrProductNotFound.Code() {
			t.Errorf("code = %v, want %q", body["code"], repository.ErrProductNotFound.Code())
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
		if body["code"] != repository.ErrProductNotFound.Code() {
			t.Errorf("code = %v, want %q", body["code"], repository.ErrProductNotFound.Code())
		}
	})
}

func TestProductController_GetProducts(t *testing.T) {
	t.Run("returns 200 with the requested page of the catalog, without the stock counts", func(t *testing.T) {
		page := 1
		size := 2
		id := uint64(7)
		totalElements := int64(3)
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productPage := &dto.ProductPageDTO{Products: []*model.Product{testProduct(t, id, 10, 4)}, TotalElements: totalElements}
		productService.EXPECT().GetProducts(gomock.Any(), page, size).Return(productPage, nil).Times(1)
		app := newProductTestApp(productService)

		req := httptest.NewRequest("GET", "/products?page=1&size=2", nil)

		resp, err := app.Test(req)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if resp.StatusCode != 200 {
			t.Errorf("status = %d, want 200", resp.StatusCode)
		}
		body := decodeBody(t, resp.Body)
		if body["page"] != float64(page) {
			t.Errorf("page = %v, want %d", body["page"], page)
		}
		if body["size"] != float64(size) {
			t.Errorf("size = %v, want %d", body["size"], size)
		}
		if body["totalElements"] != float64(totalElements) {
			t.Errorf("totalElements = %v, want %d", body["totalElements"], totalElements)
		}
		if body["totalPages"] != float64(2) {
			t.Errorf("totalPages = %v, want 2", body["totalPages"])
		}
		items, ok := body["items"].([]any)
		if !ok || len(items) != 1 {
			t.Fatalf("items = %v, want one product", body["items"])
		}
		item := items[0].(map[string]any)
		if item["id"] != float64(id) {
			t.Errorf("items[0].id = %v, want %d", item["id"], id)
		}
		if item["name"] != "Widget" {
			t.Errorf("items[0].name = %v, want %q", item["name"], "Widget")
		}
		if item["available"] != float64(6) {
			t.Errorf("items[0].available = %v, want 6", item["available"])
		}
		if item["price"] == nil {
			t.Errorf("items[0].price is missing")
		}
		for _, internalField := range []string{"quantity", "reserved"} {
			if _, present := item[internalField]; present {
				t.Errorf("items[0] exposes %q", internalField)
			}
		}
	})

	t.Run("asks for the first page of the default size when the request names none", func(t *testing.T) {
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productPage := &dto.ProductPageDTO{Products: []*model.Product{}, TotalElements: 0}
		productService.EXPECT().GetProducts(gomock.Any(), 0, defaultPageSize).Return(productPage, nil).Times(1)
		app := newProductTestApp(productService)

		req := httptest.NewRequest("GET", "/products", nil)

		resp, err := app.Test(req)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if resp.StatusCode != 200 {
			t.Errorf("status = %d, want 200", resp.StatusCode)
		}
		body := decodeBody(t, resp.Body)
		if items, ok := body["items"].([]any); !ok || len(items) != 0 {
			t.Errorf("items = %v, want an empty list", body["items"])
		}
		if body["totalPages"] != float64(0) {
			t.Errorf("totalPages = %v, want 0", body["totalPages"])
		}
	})

	t.Run("returns 400 without calling the service when page or size isn't an integer", func(t *testing.T) {
		for _, query := range []string{"page=first", "size=many"} {
			productService := mocks.NewMockProductService(gomock.NewController(t))
			productService.EXPECT().GetProducts(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
			app := newProductTestApp(productService)

			req := httptest.NewRequest("GET", "/products?"+query, nil)

			resp, err := app.Test(req)
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if resp.StatusCode != 400 {
				t.Errorf("%s: status = %d, want 400", query, resp.StatusCode)
			}
			body := decodeBody(t, resp.Body)
			if body["code"] != httperror.ErrInvalidQueryParam.Code() {
				t.Errorf("%s: code = %v, want %q", query, body["code"], httperror.ErrInvalidQueryParam.Code())
			}
		}
	})

	t.Run("returns 400 when the service rejects the page", func(t *testing.T) {
		size := 101
		productService := mocks.NewMockProductService(gomock.NewController(t))
		productService.EXPECT().GetProducts(gomock.Any(), 0, size).Return(nil, application.ErrInvalidPage).Times(1)
		app := newProductTestApp(productService)

		req := httptest.NewRequest("GET", "/products?size=101", nil)

		resp, err := app.Test(req)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if resp.StatusCode != 400 {
			t.Errorf("status = %d, want 400", resp.StatusCode)
		}
		body := decodeBody(t, resp.Body)
		if body["code"] != application.ErrInvalidPage.Code() {
			t.Errorf("code = %v, want %q", body["code"], application.ErrInvalidPage.Code())
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
		if body["code"] != repository.ErrProductNotFound.Code() {
			t.Errorf("code = %v, want %q", body["code"], repository.ErrProductNotFound.Code())
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
		if body["code"] != model.ErrProductReserved.Code() {
			t.Errorf("code = %v, want %q", body["code"], model.ErrProductReserved.Code())
		}
	})
}

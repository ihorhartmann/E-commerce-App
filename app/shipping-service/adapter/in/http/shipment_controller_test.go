package http_test

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"go.uber.org/mock/gomock"

	inhttp "shippingservice/adapter/in/http"
	"shippingservice/adapter/in/http/auth"
	"shippingservice/adapter/in/http/httperror"
	"shippingservice/adapter/in/http/middleware"
	"shippingservice/application/port/in/dto"
	mocks "shippingservice/application/port/in/mocks"
	"shippingservice/application/port/out/repository"
	"shippingservice/application/service"
	"shippingservice/domain/apperror"
	"shippingservice/domain/model"
	"shippingservice/domain/value"
)

func testAddress(t *testing.T) value.Address {
	t.Helper()
	address, err := value.NewAddress("Kyiv", "01001", "Khreshchatyk 1", "")
	if err != nil {
		t.Fatalf("unexpected error building test address: %v", err)
	}
	return address
}

// testShipment builds a shipment of order 100; a cancelled one is cancelled with its order.
func testShipment(t *testing.T, id uint64, status model.ShipmentStatus, holder model.ShipmentHolder, courierID uuid.UUID) *model.Shipment {
	t.Helper()
	now := time.Now()
	cancellationReason := model.NoCancellationReason
	if status == model.Cancelled {
		cancellationReason = model.OrderCancelled
	}
	shipment, err := model.NewExistingShipment(id, 100, courierID, testAddress(t), status, holder, cancellationReason, now, now, 0)
	if err != nil {
		t.Fatalf("unexpected error building test shipment: %v", err)
	}
	return shipment
}

// newShipmentTestApp stands in for AuthMiddleware by putting the given principal into every request, or none when
// it is nil.
func newShipmentTestApp(shipmentService *mocks.MockShipmentService, principal *auth.Principal) *gin.Engine {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(middleware.HandleMiddlewareError, func(c *gin.Context) {
		if principal != nil {
			c.Set(auth.PrincipalContextKey, *principal)
		}
	}, middleware.HandleControllerError)
	inhttp.NewShipmentController(shipmentService).RegisterRoutes(router.Group("/api/v1"))
	return router
}

func courierPrincipal(userID uuid.UUID) *auth.Principal {
	return &auth.Principal{UserID: userID, Roles: []string{"COURIER"}}
}

func decodeShipmentBody(t *testing.T, body *httptest.ResponseRecorder) map[string]any {
	t.Helper()
	var decoded map[string]any
	if err := json.Unmarshal(body.Body.Bytes(), &decoded); err != nil {
		t.Fatalf("failed to decode response body: %v", err)
	}
	return decoded
}

func decodeAddress(t *testing.T, body map[string]any) map[string]any {
	t.Helper()
	address, ok := body["address"].(map[string]any)
	if !ok {
		t.Fatalf("address = %v, want an object", body["address"])
	}
	return address
}

func TestShipmentController_GetShipment(t *testing.T) {
	t.Run("returns 200 with the full address to the courier the shipment is assigned to", func(t *testing.T) {
		id := uint64(1)
		courierID := uuid.New()
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipment := testShipment(t, id, model.InDelivery, model.HolderCourier, courierID)
		shipmentService.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)
		app := newShipmentTestApp(shipmentService, courierPrincipal(courierID))

		req := httptest.NewRequest("GET", "/api/v1/shipments/1", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 200 {
			t.Errorf("status = %d, want 200", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["id"] != float64(id) {
			t.Errorf("id = %v, want %d", body["id"], id)
		}
		if body["status"] != string(model.InDelivery) {
			t.Errorf("status = %v, want %q", body["status"], model.InDelivery)
		}
		if address := decodeAddress(t, body); address["line1"] != shipment.Address().Line1() {
			t.Errorf("address.line1 = %v, want %q", address["line1"], shipment.Address().Line1())
		}
	})

	t.Run("returns 200 without the street to a courier the shipment is not assigned to", func(t *testing.T) {
		id := uint64(1)
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipment := testShipment(t, id, model.Created, model.HolderInventory, uuid.Nil)
		shipmentService.EXPECT().GetByID(gomock.Any(), id).Return(shipment, nil).Times(1)
		app := newShipmentTestApp(shipmentService, courierPrincipal(uuid.New()))

		req := httptest.NewRequest("GET", "/api/v1/shipments/1", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 200 {
			t.Errorf("status = %d, want 200", resp.Code)
		}
		address := decodeAddress(t, decodeShipmentBody(t, resp))
		if address["city"] != shipment.Address().City() {
			t.Errorf("address.city = %v, want %q", address["city"], shipment.Address().City())
		}
		if _, present := address["line1"]; present {
			t.Errorf("address.line1 = %v, want it hidden", address["line1"])
		}
	})

	t.Run("returns 404 when the shipment is not found", func(t *testing.T) {
		id := uint64(999)
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().GetByID(gomock.Any(), id).Return(nil, repository.ErrShipmentNotFound).Times(1)
		app := newShipmentTestApp(shipmentService, courierPrincipal(uuid.New()))

		req := httptest.NewRequest("GET", "/api/v1/shipments/999", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 404 {
			t.Errorf("status = %d, want 404", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["code"] != repository.ErrShipmentNotFound.Code() {
			t.Errorf("code = %v, want %q", body["code"], repository.ErrShipmentNotFound.Code())
		}
	})

	t.Run("returns 400 when the id param is invalid", func(t *testing.T) {
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().GetByID(gomock.Any(), gomock.Any()).Times(0)
		app := newShipmentTestApp(shipmentService, courierPrincipal(uuid.New()))

		req := httptest.NewRequest("GET", "/api/v1/shipments/abc", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 400 {
			t.Errorf("status = %d, want 400", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["code"] != httperror.ErrInvalidPathParam.Code() {
			t.Errorf("code = %v, want %q", body["code"], httperror.ErrInvalidPathParam.Code())
		}
	})

	t.Run("returns 500 without calling the service when no principal reached the handler", func(t *testing.T) {
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().GetByID(gomock.Any(), gomock.Any()).Times(0)
		app := newShipmentTestApp(shipmentService, nil)

		req := httptest.NewRequest("GET", "/api/v1/shipments/1", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 500 {
			t.Errorf("status = %d, want 500", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["code"] != auth.ErrMissingPrincipal.Code() {
			t.Errorf("code = %v, want %q", body["code"], apperror.ErrInternal.Code())
		}
	})
}

func TestShipmentController_GetAvailableShipments(t *testing.T) {
	t.Run("returns 200 with the requested page, without the streets", func(t *testing.T) {
		page := 1
		size := 2
		id := uint64(7)
		totalElements := int64(3)
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipment := testShipment(t, id, model.Created, model.HolderInventory, uuid.Nil)
		shipmentPage := &dto.ShipmentPageDTO{Shipments: []*model.Shipment{shipment}, TotalElements: totalElements}
		shipmentService.EXPECT().GetAvailableShipments(gomock.Any(), page, size).Return(shipmentPage, nil).Times(1)
		app := newShipmentTestApp(shipmentService, courierPrincipal(uuid.New()))

		req := httptest.NewRequest("GET", "/api/v1/shipments/available?page=1&size=2", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 200 {
			t.Errorf("status = %d, want 200", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["page"] != float64(page) || body["size"] != float64(size) {
			t.Errorf("page/size = %v/%v, want %d/%d", body["page"], body["size"], page, size)
		}
		if body["totalElements"] != float64(totalElements) {
			t.Errorf("totalElements = %v, want %d", body["totalElements"], totalElements)
		}
		if body["totalPages"] != float64(2) {
			t.Errorf("totalPages = %v, want 2", body["totalPages"])
		}
		items, ok := body["items"].([]any)
		if !ok || len(items) != 1 {
			t.Fatalf("items = %v, want one shipment", body["items"])
		}
		item := items[0].(map[string]any)
		if item["id"] != float64(id) {
			t.Errorf("items[0].id = %v, want %d", item["id"], id)
		}
		address := decodeAddress(t, item)
		if address["postalCode"] != shipment.Address().PostalCode() {
			t.Errorf("items[0].address.postalCode = %v, want %q", address["postalCode"], shipment.Address().PostalCode())
		}
		if _, present := address["line1"]; present {
			t.Errorf("items[0].address.line1 = %v, want it hidden", address["line1"])
		}
	})

	t.Run("uses the first page and the default size when the request does not name them", func(t *testing.T) {
		firstPage := 0
		defaultSize := 20
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentPage := &dto.ShipmentPageDTO{Shipments: []*model.Shipment{testShipment(t, 1, model.Created, model.HolderInventory, uuid.Nil)}, TotalElements: 1}
		shipmentService.EXPECT().GetAvailableShipments(gomock.Any(), firstPage, defaultSize).Return(shipmentPage, nil).Times(1)
		app := newShipmentTestApp(shipmentService, courierPrincipal(uuid.New()))

		req := httptest.NewRequest("GET", "/api/v1/shipments/available", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 200 {
			t.Errorf("status = %d, want 200", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["page"] != float64(firstPage) || body["size"] != float64(defaultSize) {
			t.Errorf("page/size = %v/%v, want %d/%d", body["page"], body["size"], firstPage, defaultSize)
		}
	})

	t.Run("returns 400 without calling the service when page or size is not an integer", func(t *testing.T) {
		for _, query := range []string{"page=first", "size=many"} {
			shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
			shipmentService.EXPECT().GetAvailableShipments(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
			app := newShipmentTestApp(shipmentService, courierPrincipal(uuid.New()))

			req := httptest.NewRequest("GET", "/api/v1/shipments/available?"+query, nil)
			resp := httptest.NewRecorder()
			app.ServeHTTP(resp, req)

			if resp.Code != 400 {
				t.Errorf("%s: status = %d, want 400", query, resp.Code)
			}
			body := decodeShipmentBody(t, resp)
			if body["code"] != httperror.ErrInvalidQueryParam.Code() {
				t.Errorf("%s: code = %v, want %q", query, body["code"], httperror.ErrInvalidQueryParam.Code())
			}
		}
	})

	t.Run("returns 400 when the service rejects the page", func(t *testing.T) {
		size := 101
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().GetAvailableShipments(gomock.Any(), 0, size).Return(nil, service.ErrInvalidPage).Times(1)
		app := newShipmentTestApp(shipmentService, courierPrincipal(uuid.New()))

		req := httptest.NewRequest("GET", "/api/v1/shipments/available?size=101", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 400 {
			t.Errorf("status = %d, want 400", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["code"] != service.ErrInvalidPage.Code() {
			t.Errorf("code = %v, want %q", body["code"], service.ErrInvalidPage.Code())
		}
	})
}

func TestShipmentController_GetCourierActiveShipments(t *testing.T) {
	t.Run("returns 200 with the calling courier's shipments, with the streets", func(t *testing.T) {
		courierID := uuid.New()
		page := 0
		size := 5
		id := uint64(9)
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipment := testShipment(t, id, model.InDelivery, model.HolderCourier, courierID)
		shipmentPage := &dto.ShipmentPageDTO{Shipments: []*model.Shipment{shipment}, TotalElements: 1}
		shipmentService.EXPECT().GetCourierActiveShipments(gomock.Any(), courierID, page, size).Return(shipmentPage, nil).Times(1)
		app := newShipmentTestApp(shipmentService, courierPrincipal(courierID))

		req := httptest.NewRequest("GET", "/api/v1/shipments/mine?page=0&size=5", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 200 {
			t.Errorf("status = %d, want 200", resp.Code)
		}
		items, ok := decodeShipmentBody(t, resp)["items"].([]any)
		if !ok || len(items) != 1 {
			t.Fatalf("items = %v, want one shipment", items)
		}
		item := items[0].(map[string]any)
		if item["id"] != float64(id) {
			t.Errorf("items[0].id = %v, want %d", item["id"], id)
		}
		if address := decodeAddress(t, item); address["line1"] != shipment.Address().Line1() {
			t.Errorf("items[0].address.line1 = %v, want %q", address["line1"], shipment.Address().Line1())
		}
	})

	t.Run("returns 400 without calling the service when page is not an integer", func(t *testing.T) {
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().GetCourierActiveShipments(gomock.Any(), gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		app := newShipmentTestApp(shipmentService, courierPrincipal(uuid.New()))

		req := httptest.NewRequest("GET", "/api/v1/shipments/mine?page=first", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 400 {
			t.Errorf("status = %d, want 400", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["code"] != httperror.ErrInvalidQueryParam.Code() {
			t.Errorf("code = %v, want %q", body["code"], httperror.ErrInvalidQueryParam.Code())
		}
	})

	t.Run("returns 500 without calling the service when no principal reached the handler", func(t *testing.T) {
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().GetCourierActiveShipments(gomock.Any(), gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		app := newShipmentTestApp(shipmentService, nil)

		req := httptest.NewRequest("GET", "/api/v1/shipments/mine", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 500 {
			t.Errorf("status = %d, want 500", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["code"] != auth.ErrMissingPrincipal.Code() {
			t.Errorf("code = %v, want %q", body["code"], apperror.ErrInternal.Code())
		}
	})
}

func TestShipmentController_Pickup(t *testing.T) {
	t.Run("returns 200 with the updated shipment", func(t *testing.T) {
		id := uint64(1)
		courierID := uuid.New()
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipment := testShipment(t, id, model.InDelivery, model.HolderCourier, courierID)
		shipmentService.EXPECT().StartDelivery(gomock.Any(), id, courierID).Return(shipment, nil).Times(1)
		app := newShipmentTestApp(shipmentService, courierPrincipal(courierID))

		req := httptest.NewRequest("POST", "/api/v1/shipments/1/pickup", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 200 {
			t.Errorf("status = %d, want 200", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["id"] != float64(id) {
			t.Errorf("id = %v, want %d", body["id"], id)
		}
		if body["status"] != string(model.InDelivery) {
			t.Errorf("status = %v, want %q", body["status"], model.InDelivery)
		}
		if body["holder"] != string(model.HolderCourier) {
			t.Errorf("holder = %v, want %q", body["holder"], model.HolderCourier)
		}
	})

	t.Run("returns 400 when the id param is invalid", func(t *testing.T) {
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().StartDelivery(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		app := newShipmentTestApp(shipmentService, courierPrincipal(uuid.New()))

		req := httptest.NewRequest("POST", "/api/v1/shipments/abc/pickup", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 400 {
			t.Errorf("status = %d, want 400", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["code"] != httperror.ErrInvalidPathParam.Code() {
			t.Errorf("code = %v, want %q", body["code"], httperror.ErrInvalidPathParam.Code())
		}
	})

	t.Run("returns 404 when the shipment is not found", func(t *testing.T) {
		id := uint64(999)
		courierID := uuid.New()
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().StartDelivery(gomock.Any(), id, courierID).Return(nil, repository.ErrShipmentNotFound).Times(1)
		app := newShipmentTestApp(shipmentService, courierPrincipal(courierID))

		req := httptest.NewRequest("POST", "/api/v1/shipments/999/pickup", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 404 {
			t.Errorf("status = %d, want 404", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["code"] != repository.ErrShipmentNotFound.Code() {
			t.Errorf("code = %v, want %q", body["code"], repository.ErrShipmentNotFound.Code())
		}
	})

	t.Run("returns 409 when the status transition is invalid", func(t *testing.T) {
		id := uint64(1)
		courierID := uuid.New()
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().
			StartDelivery(gomock.Any(), id, courierID).
			Return(nil, model.ErrInvalidStatusTransition).
			Times(1)
		app := newShipmentTestApp(shipmentService, courierPrincipal(courierID))

		req := httptest.NewRequest("POST", "/api/v1/shipments/1/pickup", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 409 {
			t.Errorf("status = %d, want 409", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["code"] != model.ErrInvalidStatusTransition.Code() {
			t.Errorf("code = %v, want %q", body["code"], model.ErrInvalidStatusTransition.Code())
		}
	})

	t.Run("returns 409 when another courier picked the shipment up first", func(t *testing.T) {
		id := uint64(1)
		courierID := uuid.New()
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().StartDelivery(gomock.Any(), id, courierID).Return(nil, service.ErrAlreadyPickedUp).Times(1)
		app := newShipmentTestApp(shipmentService, courierPrincipal(courierID))

		req := httptest.NewRequest("POST", "/api/v1/shipments/1/pickup", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != http.StatusConflict {
			t.Errorf("status = %d, want %d", resp.Code, http.StatusConflict)
		}
		body := decodeShipmentBody(t, resp)
		if body["code"] != service.ErrAlreadyPickedUp.Code() {
			t.Errorf("code = %v, want %q", body["code"], service.ErrAlreadyPickedUp.Code())
		}
	})

	t.Run("returns 503 when the shipment was changed concurrently", func(t *testing.T) {
		id := uint64(1)
		courierID := uuid.New()
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().StartDelivery(gomock.Any(), id, courierID).Return(nil, repository.ErrConcurrentUpdate).Times(1)
		app := newShipmentTestApp(shipmentService, courierPrincipal(courierID))

		req := httptest.NewRequest("POST", "/api/v1/shipments/1/pickup", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != http.StatusServiceUnavailable {
			t.Errorf("status = %d, want %d", resp.Code, http.StatusServiceUnavailable)
		}
		body := decodeShipmentBody(t, resp)
		if body["code"] != repository.ErrConcurrentUpdate.Code() {
			t.Errorf("code = %v, want %q", body["code"], repository.ErrConcurrentUpdate.Code())
		}
	})

	t.Run("returns 500 when an unexpected error occurs", func(t *testing.T) {
		id := uint64(1)
		courierID := uuid.New()
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().StartDelivery(gomock.Any(), id, courierID).Return(nil, errors.New("db down")).Times(1)
		app := newShipmentTestApp(shipmentService, courierPrincipal(courierID))

		req := httptest.NewRequest("POST", "/api/v1/shipments/1/pickup", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 500 {
			t.Errorf("status = %d, want 500", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["code"] != apperror.ErrInternal.Code() {
			t.Errorf("code = %v, want %q", body["code"], apperror.ErrInternal.Code())
		}
	})
}

func TestShipmentController_Deliver(t *testing.T) {
	t.Run("returns 200 with the updated shipment", func(t *testing.T) {
		id := uint64(1)
		courierID := uuid.New()
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipment := testShipment(t, id, model.Delivered, model.HolderCourier, courierID)
		shipmentService.EXPECT().Deliver(gomock.Any(), id, courierID).Return(shipment, nil).Times(1)
		app := newShipmentTestApp(shipmentService, courierPrincipal(courierID))

		req := httptest.NewRequest("POST", "/api/v1/shipments/1/deliver", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 200 {
			t.Errorf("status = %d, want 200", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["status"] != string(model.Delivered) {
			t.Errorf("status = %v, want %q", body["status"], model.Delivered)
		}
	})

	t.Run("returns 403 when the shipment is assigned to another courier", func(t *testing.T) {
		id := uint64(1)
		courierID := uuid.New()
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().Deliver(gomock.Any(), id, courierID).Return(nil, service.ErrNotAssignedCourier).Times(1)
		app := newShipmentTestApp(shipmentService, courierPrincipal(courierID))

		req := httptest.NewRequest("POST", "/api/v1/shipments/1/deliver", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != http.StatusForbidden {
			t.Errorf("status = %d, want %d", resp.Code, http.StatusForbidden)
		}
		body := decodeShipmentBody(t, resp)
		if body["code"] != service.ErrNotAssignedCourier.Code() {
			t.Errorf("code = %v, want %q", body["code"], service.ErrNotAssignedCourier.Code())
		}
	})

	t.Run("returns 500 without calling the service when no principal reached the handler", func(t *testing.T) {
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().Deliver(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		app := newShipmentTestApp(shipmentService, nil)

		req := httptest.NewRequest("POST", "/api/v1/shipments/1/deliver", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 500 {
			t.Errorf("status = %d, want 500", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["code"] != auth.ErrMissingPrincipal.Code() {
			t.Errorf("code = %v, want %q", body["code"], apperror.ErrInternal.Code())
		}
	})
}

func TestShipmentController_Lose(t *testing.T) {
	t.Run("returns 200 with the updated shipment", func(t *testing.T) {
		id := uint64(1)
		courierID := uuid.New()
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipment := testShipment(t, id, model.Lost, model.HolderCourier, courierID)
		shipmentService.EXPECT().Lose(gomock.Any(), id, courierID).Return(shipment, nil).Times(1)
		app := newShipmentTestApp(shipmentService, courierPrincipal(courierID))

		req := httptest.NewRequest("POST", "/api/v1/shipments/1/lose", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 200 {
			t.Errorf("status = %d, want 200", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["status"] != string(model.Lost) {
			t.Errorf("status = %v, want %q", body["status"], model.Lost)
		}
	})
}

func TestShipmentController_Cancel(t *testing.T) {
	t.Run("returns 200 with the shipment cancelled for the reason the courier gave", func(t *testing.T) {
		id := uint64(1)
		courierID := uuid.New()
		reason := model.RecipientUnavailable
		now := time.Now()
		shipment, err := model.NewExistingShipment(id, 100, courierID, testAddress(t), model.Cancelled, model.HolderCourier, reason, now, now, 1)
		if err != nil {
			t.Fatalf("unexpected error building test shipment: %v", err)
		}
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().Cancel(gomock.Any(), id, courierID, reason).Return(shipment, nil).Times(1)
		app := newShipmentTestApp(shipmentService, courierPrincipal(courierID))

		req := httptest.NewRequest("POST", "/api/v1/shipments/1/cancel", strings.NewReader(`{"reason":"RecipientUnavailable"}`))
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 200 {
			t.Errorf("status = %d, want 200", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["status"] != string(model.Cancelled) {
			t.Errorf("status = %v, want %q", body["status"], model.Cancelled)
		}
		if body["holder"] != string(model.HolderCourier) {
			t.Errorf("holder = %v, want %q", body["holder"], model.HolderCourier)
		}
		if body["cancellationReason"] != string(reason) {
			t.Errorf("cancellationReason = %v, want %q", body["cancellationReason"], reason)
		}
	})

	t.Run("returns 400 when the service rejects the reason", func(t *testing.T) {
		id := uint64(1)
		courierID := uuid.New()
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().Cancel(gomock.Any(), id, courierID, model.OrderCancelled).
			Return(nil, service.ErrInvalidCancellationReason).Times(1)
		app := newShipmentTestApp(shipmentService, courierPrincipal(courierID))

		req := httptest.NewRequest("POST", "/api/v1/shipments/1/cancel", strings.NewReader(`{"reason":"OrderCancelled"}`))
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != http.StatusBadRequest {
			t.Errorf("status = %d, want %d", resp.Code, http.StatusBadRequest)
		}
		body := decodeShipmentBody(t, resp)
		if body["code"] != service.ErrInvalidCancellationReason.Code() {
			t.Errorf("code = %v, want %q", body["code"], service.ErrInvalidCancellationReason.Code())
		}
	})

	t.Run("returns 400 without calling the service when the body is not JSON", func(t *testing.T) {
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().Cancel(gomock.Any(), gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		app := newShipmentTestApp(shipmentService, courierPrincipal(uuid.New()))

		req := httptest.NewRequest("POST", "/api/v1/shipments/1/cancel", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != http.StatusBadRequest {
			t.Errorf("status = %d, want %d", resp.Code, http.StatusBadRequest)
		}
		body := decodeShipmentBody(t, resp)
		if body["code"] != httperror.ErrMalformedRequestBody.Code() {
			t.Errorf("code = %v, want %q", body["code"], httperror.ErrMalformedRequestBody.Code())
		}
	})

	t.Run("returns 403 when the shipment is assigned to another courier", func(t *testing.T) {
		id := uint64(1)
		courierID := uuid.New()
		reason := model.RecipientRefused
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().Cancel(gomock.Any(), id, courierID, reason).Return(nil, service.ErrNotAssignedCourier).Times(1)
		app := newShipmentTestApp(shipmentService, courierPrincipal(courierID))

		req := httptest.NewRequest("POST", "/api/v1/shipments/1/cancel", strings.NewReader(`{"reason":"RecipientRefused"}`))
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != http.StatusForbidden {
			t.Errorf("status = %d, want %d", resp.Code, http.StatusForbidden)
		}
		body := decodeShipmentBody(t, resp)
		if body["code"] != service.ErrNotAssignedCourier.Code() {
			t.Errorf("code = %v, want %q", body["code"], service.ErrNotAssignedCourier.Code())
		}
	})
}

func TestShipmentController_ConfirmReturnedToInventory(t *testing.T) {
	t.Run("returns 200 with the updated shipment", func(t *testing.T) {
		id := uint64(1)
		courierID := uuid.New()
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipment := testShipment(t, id, model.Cancelled, model.HolderInventory, courierID)
		shipmentService.EXPECT().ConfirmReturnedToInventory(gomock.Any(), id, courierID).Return(shipment, nil).Times(1)
		app := newShipmentTestApp(shipmentService, courierPrincipal(courierID))

		req := httptest.NewRequest("POST", "/api/v1/shipments/1/return", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 200 {
			t.Errorf("status = %d, want 200", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["status"] != string(model.Cancelled) {
			t.Errorf("status = %v, want %q", body["status"], model.Cancelled)
		}
		if body["holder"] != string(model.HolderInventory) {
			t.Errorf("holder = %v, want %q", body["holder"], model.HolderInventory)
		}
	})
}

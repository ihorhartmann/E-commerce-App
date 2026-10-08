package http_test

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"go.uber.org/mock/gomock"

	inhttp "shippingservice/adapter/in/http"
	"shippingservice/adapter/in/http/auth"
	"shippingservice/adapter/in/http/middleware"
	mocks "shippingservice/application/port/in/mocks"
	"shippingservice/application/port/out/repository"
	"shippingservice/application/service"
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

func testShipment(t *testing.T, id uint64, status model.ShipmentStatus, holder model.ShipmentHolder, courierID uuid.UUID) *model.Shipment {
	t.Helper()
	now := time.Now()
	shipment, err := model.NewExistingShipment(id, 100, uuid.New(), courierID, "TRACK-1", testAddress(t), status, holder, now, now, 0)
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
	router.Use(middleware.ErrorHandlerMiddleware(), func(c *gin.Context) {
		if principal != nil {
			c.Set(auth.PrincipalContextKey, *principal)
		}
	})
	inhttp.NewShipmentController(shipmentService).RegisterRoutes(router.Group("/api/v1"))
	return router
}

func clientPrincipal(userID uuid.UUID) *auth.Principal {
	return &auth.Principal{UserID: userID, Roles: []string{"CLIENT"}}
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

func TestShipmentController_GetByTrackingNumber(t *testing.T) {
	t.Run("returns 200 with the customer's own shipment", func(t *testing.T) {
		trackingNumber := "TRACK-1"
		customerID := uuid.New()
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipment := testShipment(t, 1, model.Created, model.HolderInventory, uuid.Nil)
		shipmentService.EXPECT().
			GetCustomerShipmentByTrackingNumber(gomock.Any(), trackingNumber, customerID).
			Return(shipment, nil).
			Times(1)
		shipmentService.EXPECT().GetByTrackingNumber(gomock.Any(), gomock.Any()).Times(0)
		app := newShipmentTestApp(shipmentService, clientPrincipal(customerID))

		req := httptest.NewRequest("GET", "/api/v1/shipments/tracking/"+trackingNumber, nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 200 {
			t.Errorf("status = %d, want 200", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["trackingNumber"] != trackingNumber {
			t.Errorf("trackingNumber = %v, want %q", body["trackingNumber"], trackingNumber)
		}
		if body["status"] != string(model.Created) {
			t.Errorf("status = %v, want %q", body["status"], model.Created)
		}
	})

	t.Run("returns 200 with any shipment to a courier", func(t *testing.T) {
		trackingNumber := "TRACK-1"
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipment := testShipment(t, 1, model.Created, model.HolderInventory, uuid.Nil)
		shipmentService.EXPECT().GetByTrackingNumber(gomock.Any(), trackingNumber).Return(shipment, nil).Times(1)
		shipmentService.EXPECT().GetCustomerShipmentByTrackingNumber(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		app := newShipmentTestApp(shipmentService, courierPrincipal(uuid.New()))

		req := httptest.NewRequest("GET", "/api/v1/shipments/tracking/"+trackingNumber, nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 200 {
			t.Errorf("status = %d, want 200", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["trackingNumber"] != trackingNumber {
			t.Errorf("trackingNumber = %v, want %q", body["trackingNumber"], trackingNumber)
		}
	})

	t.Run("returns 400 when the tracking number is invalid", func(t *testing.T) {
		trackingNumber := "bad"
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().
			GetByTrackingNumber(gomock.Any(), trackingNumber).
			Return(nil, service.ErrInvalidTrackingNumber).
			Times(1)
		app := newShipmentTestApp(shipmentService, courierPrincipal(uuid.New()))

		req := httptest.NewRequest("GET", "/api/v1/shipments/tracking/"+trackingNumber, nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 400 {
			t.Errorf("status = %d, want 400", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["error"] != service.ErrInvalidTrackingNumber.Error() {
			t.Errorf("error = %v, want %q", body["error"], service.ErrInvalidTrackingNumber.Error())
		}
	})

	t.Run("returns 404 when the shipment is not found or belongs to another customer", func(t *testing.T) {
		trackingNumber := "TRACK-404"
		customerID := uuid.New()
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().
			GetCustomerShipmentByTrackingNumber(gomock.Any(), trackingNumber, customerID).
			Return(nil, repository.ErrShipmentNotFound).
			Times(1)
		app := newShipmentTestApp(shipmentService, clientPrincipal(customerID))

		req := httptest.NewRequest("GET", "/api/v1/shipments/tracking/"+trackingNumber, nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 404 {
			t.Errorf("status = %d, want 404", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["error"] != repository.ErrShipmentNotFound.Error() {
			t.Errorf("error = %v, want %q", body["error"], repository.ErrShipmentNotFound.Error())
		}
	})

	t.Run("returns 500 when an unexpected error occurs", func(t *testing.T) {
		trackingNumber := "TRACK-1"
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().
			GetByTrackingNumber(gomock.Any(), trackingNumber).
			Return(nil, errors.New("db down")).
			Times(1)
		app := newShipmentTestApp(shipmentService, courierPrincipal(uuid.New()))

		req := httptest.NewRequest("GET", "/api/v1/shipments/tracking/"+trackingNumber, nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 500 {
			t.Errorf("status = %d, want 500", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["error"] != middleware.InternalServerErrorMessage {
			t.Errorf("error = %v, want %q", body["error"], middleware.InternalServerErrorMessage)
		}
	})

	t.Run("returns 500 without calling the service when no principal reached the handler", func(t *testing.T) {
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipmentService.EXPECT().GetByTrackingNumber(gomock.Any(), gomock.Any()).Times(0)
		shipmentService.EXPECT().GetCustomerShipmentByTrackingNumber(gomock.Any(), gomock.Any(), gomock.Any()).Times(0)
		app := newShipmentTestApp(shipmentService, nil)

		req := httptest.NewRequest("GET", "/api/v1/shipments/tracking/TRACK-1", nil)
		resp := httptest.NewRecorder()
		app.ServeHTTP(resp, req)

		if resp.Code != 500 {
			t.Errorf("status = %d, want 500", resp.Code)
		}
		body := decodeShipmentBody(t, resp)
		if body["error"] != middleware.InternalServerErrorMessage {
			t.Errorf("error = %v, want %q", body["error"], middleware.InternalServerErrorMessage)
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
		if body["error"] != inhttp.ErrInvalidShipmentID.Error() {
			t.Errorf("error = %v, want %q", body["error"], inhttp.ErrInvalidShipmentID.Error())
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
		if body["error"] != repository.ErrShipmentNotFound.Error() {
			t.Errorf("error = %v, want %q", body["error"], repository.ErrShipmentNotFound.Error())
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
		if body["error"] != model.ErrInvalidStatusTransition.Error() {
			t.Errorf("error = %v, want %q", body["error"], model.ErrInvalidStatusTransition.Error())
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
		if body["error"] != middleware.InternalServerErrorMessage {
			t.Errorf("error = %v, want %q", body["error"], middleware.InternalServerErrorMessage)
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
		if body["error"] != middleware.InternalServerErrorMessage {
			t.Errorf("error = %v, want %q", body["error"], middleware.InternalServerErrorMessage)
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
		if body["error"] != service.ErrNotAssignedCourier.Error() {
			t.Errorf("error = %v, want %q", body["error"], service.ErrNotAssignedCourier.Error())
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
		if body["error"] != middleware.InternalServerErrorMessage {
			t.Errorf("error = %v, want %q", body["error"], middleware.InternalServerErrorMessage)
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
	t.Run("returns 200 with the updated shipment", func(t *testing.T) {
		id := uint64(1)
		shipmentService := mocks.NewMockShipmentService(gomock.NewController(t))
		shipment := testShipment(t, id, model.Cancelled, model.HolderInventory, uuid.Nil)
		shipmentService.EXPECT().Cancel(gomock.Any(), id).Return(shipment, nil).Times(1)
		app := newShipmentTestApp(shipmentService, nil)

		req := httptest.NewRequest("POST", "/api/v1/shipments/1/cancel", nil)
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

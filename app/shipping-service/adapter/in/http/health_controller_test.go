package http_test

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	"go.uber.org/mock/gomock"

	inhttp "shippingservice/adapter/in/http"
	"shippingservice/adapter/in/http/mocks"
)

func newHealthTestRouter(db inhttp.DBPinger, ready bool) *gin.Engine {
	router := gin.New()
	healthController := inhttp.NewHealthController(db)
	healthController.RegisterRoutes(router)
	healthController.SetReady(ready)
	return router
}

func assertHealth(t *testing.T, router *gin.Engine, path string, wantStatus int, wantBody string) {
	t.Helper()
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, path, nil))

	if recorder.Code != wantStatus {
		t.Errorf("status = %d, want %d", recorder.Code, wantStatus)
	}
	var body map[string]any
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("failed to decode response body: %v", err)
	}
	if gotStatus := body["status"]; gotStatus != wantBody {
		t.Errorf("status field = %v, want %s", gotStatus, wantBody)
	}
}

func TestHealthController_Liveness(t *testing.T) {
	t.Run("returns 200 UP without checking the database, even before the startup has finished", func(t *testing.T) {
		db := mocks.NewMockDBPinger(gomock.NewController(t))
		db.EXPECT().PingContext(gomock.Any()).Times(0)

		assertHealth(t, newHealthTestRouter(db, false), "/health/liveness", http.StatusOK, "UP")
	})
}

func TestHealthController_Readiness(t *testing.T) {
	for _, path := range []string{"/health/readiness", "/health"} {
		t.Run(path+" returns 200 UP once ready and the database answers", func(t *testing.T) {
			db := mocks.NewMockDBPinger(gomock.NewController(t))
			db.EXPECT().PingContext(gomock.Any()).Return(nil).Times(1)

			assertHealth(t, newHealthTestRouter(db, true), path, http.StatusOK, "UP")
		})
	}

	t.Run("returns 503 DOWN before the startup has finished or once the shutdown has begun, without checking the database", func(t *testing.T) {
		db := mocks.NewMockDBPinger(gomock.NewController(t))
		db.EXPECT().PingContext(gomock.Any()).Times(0)

		assertHealth(t, newHealthTestRouter(db, false), "/health/readiness", http.StatusServiceUnavailable, "DOWN")
	})

	t.Run("returns 503 DOWN when the database doesn't answer", func(t *testing.T) {
		db := mocks.NewMockDBPinger(gomock.NewController(t))
		db.EXPECT().PingContext(gomock.Any()).Return(errors.New("connection refused")).Times(1)

		assertHealth(t, newHealthTestRouter(db, true), "/health/readiness", http.StatusServiceUnavailable, "DOWN")
	})
}

package http

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gofiber/fiber/v2"
	"go.uber.org/mock/gomock"

	"inventoryservice/interfaces/http/mocks"
)

func newHealthTestApp(db DBPinger, ready bool) *fiber.App {
	app := fiber.New()
	healthController := NewHealthController(db)
	healthController.RegisterRoutes(app)
	healthController.SetReady(ready)
	return app
}

func assertHealth(t *testing.T, app *fiber.App, path string, wantStatus int, wantBody string) {
	t.Helper()
	resp, err := app.Test(httptest.NewRequest(http.MethodGet, path, nil))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if resp.StatusCode != wantStatus {
		t.Errorf("status = %d, want %d", resp.StatusCode, wantStatus)
	}
	if gotStatus := decodeBody(t, resp.Body)["status"]; gotStatus != wantBody {
		t.Errorf("status field = %v, want %s", gotStatus, wantBody)
	}
}

func TestHealthController_Liveness(t *testing.T) {
	t.Run("returns 200 UP without checking the database, even before the startup has finished", func(t *testing.T) {
		db := mocks.NewMockDBPinger(gomock.NewController(t))
		db.EXPECT().PingContext(gomock.Any()).Times(0)

		assertHealth(t, newHealthTestApp(db, false), "/health/liveness", http.StatusOK, "UP")
	})
}

func TestHealthController_Readiness(t *testing.T) {
	for _, path := range []string{"/health/readiness", "/health"} {
		t.Run(path+" returns 200 UP once ready and the database answers", func(t *testing.T) {
			db := mocks.NewMockDBPinger(gomock.NewController(t))
			db.EXPECT().PingContext(gomock.Any()).Return(nil).Times(1)

			assertHealth(t, newHealthTestApp(db, true), path, http.StatusOK, "UP")
		})
	}

	t.Run("returns 503 DOWN before the startup has finished or once the shutdown has begun, without checking the database", func(t *testing.T) {
		db := mocks.NewMockDBPinger(gomock.NewController(t))
		db.EXPECT().PingContext(gomock.Any()).Times(0)

		assertHealth(t, newHealthTestApp(db, false), "/health/readiness", http.StatusServiceUnavailable, "DOWN")
	})

	t.Run("returns 503 DOWN when the database doesn't answer", func(t *testing.T) {
		db := mocks.NewMockDBPinger(gomock.NewController(t))
		db.EXPECT().PingContext(gomock.Any()).Return(errors.New("connection refused")).Times(1)

		assertHealth(t, newHealthTestApp(db, true), "/health/readiness", http.StatusServiceUnavailable, "DOWN")
	})
}

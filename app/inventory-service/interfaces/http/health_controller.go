package http

import (
	"context"
	"log/slog"
	"net/http"
	"sync/atomic"
	"time"

	dto "inventoryservice/interfaces/http/dto/health"

	"github.com/gofiber/fiber/v2"
)

const (
	statusUp   = "UP"
	statusDown = "DOWN"
	// dbPingTimeout bounds the database check, well within the timeout of whoever probes readiness
	dbPingTimeout = time.Second
)

//go:generate go run go.uber.org/mock/mockgen -source=health_controller.go -destination=mocks/mock_health_controller.go -package=mocks

// DBPinger is the database whose reachability decides readiness - *sql.DB.
type DBPinger interface {
	PingContext(ctx context.Context) error
}

// HealthController answers the probes, on the same paths and with the same bodies as Spring Boot Actuator in the
// Spring services:
//   - /health/liveness - the process is up and serving; 200 whatever the dependencies, so an outage of the database
//     never gets every replica restarted;
//   - /health/readiness (and /health) - the replica can take requests: the startup has finished, the shutdown hasn't
//     begun, and the database answers; 503 otherwise, so it is taken out of the load balancing until it can.
//
// Kafka is no part of readiness: the consumer reconnects on its own, and a request needs no Kafka.
type HealthController struct {
	db    DBPinger
	ready atomic.Bool
}

// NewHealthController returns a controller that reports not ready until SetReady(true).
func NewHealthController(db DBPinger) *HealthController {
	return &HealthController{db: db}
}

// SetReady is called by main.go with true once the startup has finished, and with false as the first step of the
// shutdown.
func (hc *HealthController) SetReady(ready bool) {
	hc.ready.Store(ready)
}

// RegisterRoutes must be called before any middleware is registered: the probes are polled every few seconds, need no
// token, and are neither traced, nor logged, nor counted in the HTTP metrics.
func (hc *HealthController) RegisterRoutes(router fiber.Router) {
	router.Get("/health", hc.Readiness)
	router.Get("/health/liveness", hc.Liveness)
	router.Get("/health/readiness", hc.Readiness)
}

func (hc *HealthController) Liveness(c *fiber.Ctx) error {
	return c.Status(http.StatusOK).JSON(dto.HealthResponse{Status: statusUp})
}

func (hc *HealthController) Readiness(c *fiber.Ctx) error {
	if !hc.ready.Load() {
		return c.Status(http.StatusServiceUnavailable).JSON(dto.HealthResponse{Status: statusDown})
	}

	ctx, cancel := context.WithTimeout(c.UserContext(), dbPingTimeout)
	defer cancel()
	if err := hc.db.PingContext(ctx); err != nil {
		slog.WarnContext(ctx, "readiness check failed", "check", "db", "error", err)
		return c.Status(http.StatusServiceUnavailable).JSON(dto.HealthResponse{Status: statusDown})
	}

	return c.Status(http.StatusOK).JSON(dto.HealthResponse{Status: statusUp})
}

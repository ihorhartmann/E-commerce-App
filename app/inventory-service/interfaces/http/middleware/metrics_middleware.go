package middleware

import (
	"net/http"
	"time"

	"inventoryservice/interfaces/http/httperror"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/utils"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

const (
	// middlewarePath is the path of the route Fiber gives a request no endpoint matched: the one its middleware is
	// registered at with app.Use. No endpoint is at /.
	middlewarePath = "/"
	// the route of a request no endpoint matched, as the Spring services name it
	routeNotFound = "NOT_FOUND"
	routeUnknown  = "UNKNOWN"
)

// durationBuckets are the bucket boundaries, in seconds, OpenTelemetry's semantic conventions give request durations
// - the same in every service, so their percentiles compare.
var durationBuckets = []float64{0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10}

// requestDuration is the metric OpenTelemetry's semantic conventions define for incoming requests, with their
// attribute names - the Spring services record the same. The meter is the global provider's (see
// infrastructure/telemetry) - until one is installed, e.g. in tests, a no-op.
var requestDuration, _ = otel.Meter("inventoryservice/interfaces/http/middleware").Float64Histogram(
	"http.server.request.duration",
	metric.WithDescription("Duration of HTTP server requests"),
	metric.WithUnit("s"),
	metric.WithExplicitBucketBoundaries(durationBuckets...))

// MetricsMiddleware must be registered first: it records every request's duration by method, route and status. An
// error still on its way to Fiber's ErrorHandler isn't the response yet, so its status is the one the handler will
// write - otelfiber, registered below, writes an error that reaches it itself.
func MetricsMiddleware(c *fiber.Ctx) error {
	start := time.Now()
	err := c.Next()

	status := c.Response().StatusCode()
	if err != nil {
		status = httperror.StatusOf(err)
	}
	requestDuration.Record(c.UserContext(), time.Since(start).Seconds(), metric.WithAttributes(
		// copied: Fiber's strings point into fasthttp's buffers, reused by the next request - the attribute outlives this one
		attribute.String("http.request.method", utils.CopyString(c.Method())),
		attribute.String("http.route", routeOf(c, status)),
		attribute.Int("http.response.status_code", status),
	))
	return err
}

// routeOf returns the path pattern of the endpoint that handled the request - /api/v1/products/:id, never the path
// itself, which would make a series per product.
func routeOf(c *fiber.Ctx, status int) string {
	if route := c.Route().Path; route != middlewarePath {
		return route
	}
	if status == http.StatusNotFound {
		return routeNotFound
	}
	return routeUnknown
}

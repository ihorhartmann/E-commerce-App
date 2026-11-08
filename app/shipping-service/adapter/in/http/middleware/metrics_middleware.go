package middleware

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

// the route of a request no endpoint matched, as the Spring services name it
const (
	routeNotFound = "NOT_FOUND"
	routeUnknown  = "UNKNOWN"
)

// durationBuckets are the bucket boundaries, in seconds, OpenTelemetry's semantic conventions give request durations
// - the same in every service, so their percentiles compare.
var durationBuckets = []float64{0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10}

// requestDuration is the metric OpenTelemetry's semantic conventions define for incoming requests, with their
// attribute names - the Spring services record the same. The meter is the global provider's (see
// infrastructure/telemetry) - until one is installed, e.g. in tests, a no-op.
var requestDuration, _ = otel.Meter("shippingservice/adapter/in/http/middleware").Float64Histogram(
	"http.server.request.duration",
	metric.WithDescription("Duration of HTTP server requests"),
	metric.WithUnit("s"),
	metric.WithExplicitBucketBoundaries(durationBuckets...))

// MetricsMiddleware must be registered above HandleMiddlewareError: it records every request's duration by method,
// route and status, which is final only once the error handlers below have written the response.
func MetricsMiddleware(c *gin.Context) {
	start := time.Now()
	c.Next()

	requestDuration.Record(c.Request.Context(), time.Since(start).Seconds(), metric.WithAttributes(
		attribute.String("http.request.method", c.Request.Method),
		attribute.String("http.route", routeOf(c)),
		attribute.Int("http.response.status_code", c.Writer.Status()),
	))
}

// routeOf returns the path pattern of the endpoint that handled the request - /api/v1/shipments/:id, never the path
// itself, which would make a series per shipment.
func routeOf(c *gin.Context) string {
	if route := c.FullPath(); route != "" {
		return route
	}
	if c.Writer.Status() == http.StatusNotFound {
		return routeNotFound
	}
	return routeUnknown
}

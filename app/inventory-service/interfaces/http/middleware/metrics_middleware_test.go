package middleware

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"

	"github.com/gofiber/fiber/v2"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"
)

var (
	installMetricReader sync.Once
	metricReader        *sdkmetric.ManualReader
)

// useMetricReader installs a meter provider reading into a manual reader, once - the package's instruments are bound
// to the first provider installed. Every test requests a route of its own, as the values add up across tests.
func useMetricReader() {
	installMetricReader.Do(func() {
		metricReader = sdkmetric.NewManualReader()
		otel.SetMeterProvider(sdkmetric.NewMeterProvider(sdkmetric.WithReader(metricReader)))
	})
}

// recordedRequests returns the attribute sets of the recorded requests whose http.route is route.
func recordedRequests(t *testing.T, route string) []attribute.Set {
	t.Helper()
	var resourceMetrics metricdata.ResourceMetrics
	if err := metricReader.Collect(context.Background(), &resourceMetrics); err != nil {
		t.Fatalf("failed to collect metrics: %v", err)
	}
	var requests []attribute.Set
	for _, scopeMetrics := range resourceMetrics.ScopeMetrics {
		for _, metric := range scopeMetrics.Metrics {
			histogram, ok := metric.Data.(metricdata.Histogram[float64])
			if metric.Name != "http.server.request.duration" || !ok {
				continue
			}
			for _, point := range histogram.DataPoints {
				if value, _ := point.Attributes.Value("http.route"); value.AsString() == route {
					requests = append(requests, point.Attributes)
				}
			}
		}
	}
	return requests
}

func newMetricsTestApp() *fiber.App {
	app := fiber.New()
	app.Use(MetricsMiddleware)
	app.Get("/products/:id", func(c *fiber.Ctx) error { return c.SendStatus(http.StatusOK) })
	app.Use(func(c *fiber.Ctx) error { return c.SendStatus(http.StatusMethodNotAllowed) })
	return app
}

func TestMetricsMiddleware(t *testing.T) {
	useMetricReader()

	t.Run("records a request by method, route pattern and status", func(t *testing.T) {
		route := "/products/:id"

		resp, err := newMetricsTestApp().Test(httptest.NewRequest(http.MethodGet, "/products/7", nil))
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("status = %d, want %d", resp.StatusCode, http.StatusOK)
		}

		requests := recordedRequests(t, route)
		if len(requests) != 1 {
			t.Fatalf("recorded requests = %d, want 1", len(requests))
		}
		if gotMethod, _ := requests[0].Value("http.request.method"); gotMethod.AsString() != http.MethodGet {
			t.Errorf("http.request.method = %q, want %q", gotMethod.AsString(), http.MethodGet)
		}
		if gotStatus, _ := requests[0].Value("http.response.status_code"); gotStatus.AsInt64() != http.StatusOK {
			t.Errorf("http.response.status_code = %d, want %d", gotStatus.AsInt64(), http.StatusOK)
		}
	})

	t.Run("records a request no endpoint matched as NOT_FOUND", func(t *testing.T) {
		app := fiber.New()
		app.Use(MetricsMiddleware)

		if _, err := app.Test(httptest.NewRequest(http.MethodGet, "/nothing-here", nil)); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if requests := recordedRequests(t, routeNotFound); len(requests) != 1 {
			t.Errorf("recorded requests = %d, want 1", len(requests))
		}
	})

	t.Run("records a request no endpoint matched with another status as UNKNOWN", func(t *testing.T) {
		if _, err := newMetricsTestApp().Test(httptest.NewRequest(http.MethodPost, "/products/7", nil)); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if requests := recordedRequests(t, routeUnknown); len(requests) != 1 {
			t.Errorf("recorded requests = %d, want 1", len(requests))
		}
	})
}

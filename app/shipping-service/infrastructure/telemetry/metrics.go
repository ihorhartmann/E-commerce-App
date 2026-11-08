package telemetry

import (
	"context"
	"time"

	"go.opentelemetry.io/contrib/instrumentation/runtime"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlpmetric/otlpmetrichttp"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
)

const (
	// metricsPath is where an OTLP/HTTP collector takes metrics, under its base URL.
	metricsPath = "/v1/metrics"
	// metricsExportInterval is how often the current values are pushed - the same step the Spring services use.
	metricsExportInterval = 15 * time.Second
)

// InitMetrics installs the global meter provider the instrumentation (HTTP, Kafka, the database pool) records with:
// every metricsExportInterval the values are sent to the collector at endpoint over OTLP/HTTP, all carrying
// serviceName. It also starts recording the Go runtime's own metrics - memory, GC, goroutines. The returned function
// pushes the last values and stops the exporter - call it on shutdown, after everything that records has stopped.
func InitMetrics(ctx context.Context, endpoint, serviceName string) (func(context.Context) error, error) {
	exporter, err := otlpmetrichttp.New(ctx, otlpmetrichttp.WithEndpointURL(endpoint+metricsPath))
	if err != nil {
		return nil, err
	}

	serviceResource, err := newServiceResource(serviceName)
	if err != nil {
		return nil, err
	}

	provider := sdkmetric.NewMeterProvider(
		sdkmetric.WithReader(sdkmetric.NewPeriodicReader(exporter, sdkmetric.WithInterval(metricsExportInterval))),
		sdkmetric.WithResource(serviceResource),
	)
	otel.SetMeterProvider(provider)

	if err = runtime.Start(runtime.WithMeterProvider(provider)); err != nil {
		return nil, err
	}

	return provider.Shutdown, nil
}

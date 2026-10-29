package telemetry

import (
	"context"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
)

// tracesPath is where an OTLP/HTTP collector takes spans, under its base URL.
const tracesPath = "/v1/traces"

// InitTracing installs the global tracer provider and propagator the instrumentation (HTTP, GORM, Kafka) uses: spans
// are batched and sent to the collector at endpoint over OTLP/HTTP, all carrying serviceName, and the trace context
// travels in W3C traceparent headers. The returned function flushes the spans still buffered and stops the exporter -
// call it on shutdown, after everything that creates spans has stopped.
func InitTracing(ctx context.Context, endpoint, serviceName string) (func(context.Context) error, error) {
	exporter, err := otlptracehttp.New(ctx, otlptracehttp.WithEndpointURL(endpoint+tracesPath))
	if err != nil {
		return nil, err
	}

	serviceResource, err := resource.Merge(resource.Default(), resource.NewSchemaless(attribute.String("service.name", serviceName)))
	if err != nil {
		return nil, err
	}

	provider := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(serviceResource),
		// a request or message that carries a traceparent follows the caller's decision
		sdktrace.WithSampler(sdktrace.ParentBased(entryPointSampler{})),
	)

	otel.SetTracerProvider(provider)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(propagation.TraceContext{}, propagation.Baggage{}))

	return provider.Shutdown, nil
}

// entryPointSampler decides for a span without a parent: it starts a trace only at an entry point - an incoming HTTP request
// or Kafka message. Any other span without a parent (a sweep's query, a migration) is dropped instead of becoming a trace of
// its own, every few minutes or seconds.
type entryPointSampler struct {
	sdktrace.Sampler
}

func (entryPointSampler) ShouldSample(parameters sdktrace.SamplingParameters) sdktrace.SamplingResult {
	decision := sdktrace.Drop
	if parameters.Kind == trace.SpanKindServer || parameters.Kind == trace.SpanKindConsumer {
		decision = sdktrace.RecordAndSample
	}

	return sdktrace.SamplingResult{
		Decision:   decision,
		Tracestate: trace.SpanContextFromContext(parameters.ParentContext).TraceState(),
	}
}

func (entryPointSampler) Description() string {
	return "EntryPointSampler"
}

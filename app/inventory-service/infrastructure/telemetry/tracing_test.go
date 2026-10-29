package telemetry

import (
	"context"
	"testing"

	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
)

func TestEntryPointSampler_ShouldSample(t *testing.T) {
	t.Run("starts a trace at an incoming request or message", func(t *testing.T) {
		for _, kind := range []trace.SpanKind{trace.SpanKindServer, trace.SpanKindConsumer} {
			got := entryPointSampler{}.ShouldSample(sdktrace.SamplingParameters{ParentContext: context.Background(), Kind: kind})

			if got.Decision != sdktrace.RecordAndSample {
				t.Errorf("kind %v: got decision %v, want %v", kind, got.Decision, sdktrace.RecordAndSample)
			}
		}
	})

	t.Run("drops any other span without a parent", func(t *testing.T) {
		for _, kind := range []trace.SpanKind{trace.SpanKindClient, trace.SpanKindProducer, trace.SpanKindInternal} {
			got := entryPointSampler{}.ShouldSample(sdktrace.SamplingParameters{ParentContext: context.Background(), Kind: kind})

			if got.Decision != sdktrace.Drop {
				t.Errorf("kind %v: got decision %v, want %v", kind, got.Decision, sdktrace.Drop)
			}
		}
	})
}

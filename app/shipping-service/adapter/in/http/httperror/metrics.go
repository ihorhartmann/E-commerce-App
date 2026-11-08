package httperror

import (
	"context"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

// failures counts the error responses by their code - a conflict on an optimistic lock, a request already in
// progress, insufficient stock - which the status alone doesn't tell apart. The Kafka consumer counts the failed
// message attempts under the same name. The meter is the global provider's (see infrastructure/telemetry) - until one
// is installed, e.g. in tests, a no-op.
var failures, _ = otel.Meter("shippingservice/adapter/in/http/httperror").Int64Counter("ecommerce.errors",
	metric.WithDescription("Failed HTTP requests and Kafka message attempts, by error code"),
	metric.WithUnit("{error}"))

func recordFailureMetric(ctx context.Context, code string) {
	failures.Add(ctx, 1, metric.WithAttributes(attribute.String("error.code", code), attribute.String("transport", "http")))
}

package telemetry

import (
	"os"

	"github.com/google/uuid"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/sdk/resource"
)

// newServiceResource describes this process to the collector, the same on its spans and its metrics: serviceName as
// service.name - shared by every replica - and the host name as service.instance.id, unique per replica (the container
// id in Docker, the pod name in Kubernetes). Without the instance id the metrics of two replicas would be the same
// series in Prometheus.
func newServiceResource(serviceName string) (*resource.Resource, error) {
	instanceID, err := os.Hostname()
	if err != nil || instanceID == "" {
		instanceID = uuid.NewString()
	}

	return resource.Merge(resource.Default(), resource.NewSchemaless(
		attribute.String("service.name", serviceName),
		attribute.String("service.instance.id", instanceID),
	))
}

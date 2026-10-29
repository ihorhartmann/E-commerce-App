package kafkago

import (
	kafkago "github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel/propagation"
)

// headerCarrier lets OpenTelemetry's propagator read and write the trace context (the traceparent header) in a
// kafka-go message's headers - the propagator knows the format, the carrier where it goes. OpenTelemetry ships
// carriers for HTTP headers and maps, not for kafka-go's []Header.
type headerCarrier struct {
	propagation.TextMapCarrier
	headers *[]kafkago.Header
}

func newHeaderCarrier(headers *[]kafkago.Header) headerCarrier {
	return headerCarrier{headers: headers}
}

func (c headerCarrier) Get(key string) string {
	for _, header := range *c.headers {
		if header.Key == key {
			return string(header.Value)
		}
	}
	return ""
}

// Set replaces a header of the same key rather than adding a second one: a message read back with two traceparent
// headers would leave it to the reader which of them counts.
func (c headerCarrier) Set(key string, value string) {
	for i, header := range *c.headers {
		if header.Key == key {
			(*c.headers)[i].Value = []byte(value)
			return
		}
	}
	*c.headers = append(*c.headers, kafkago.Header{Key: key, Value: []byte(value)})
}

func (c headerCarrier) Keys() []string {
	keys := make([]string, 0, len(*c.headers))
	for _, header := range *c.headers {
		keys = append(keys, header.Key)
	}
	return keys
}

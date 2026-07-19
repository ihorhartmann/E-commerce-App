package kafkago

const (
	eventTypeHeader     = "eventType"
	correlationIDHeader = "correlationId"
	eventIDHeader       = "eventId"

	ShippingCommandsTopic = "shipping.commands"
	ShippingEventsTopic   = "shipping.events"
	ConsumerGroupID       = "shipping-service"
)

package kafka

const (
	MessageTypeHeader   = "messageType"
	CorrelationIDHeader = "correlationId"
	MessageIDHeader     = "messageId"

	ShippingCommandsTopic = "shipping.commands"
	ShippingEventsTopic   = "shipping.events"
	ConsumerGroupID       = "shipping-service"
)

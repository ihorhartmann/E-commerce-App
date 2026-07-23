package kafkago

const (
	messageTypeHeader   = "messageType"
	correlationIDHeader = "correlationId"
	messageIDHeader     = "messageId"

	ShippingCommandsTopic = "shipping.commands"
	ShippingEventsTopic   = "shipping.events"
	ConsumerGroupID       = "shipping-service"
)

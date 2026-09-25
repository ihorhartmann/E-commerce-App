package kafka

const (
	MessageTypeHeader   = "messageType"
	CorrelationIDHeader = "correlationId"
	MessageIDHeader     = "messageId"

	ShippingCommandsTopic = "shipping.commands"
	ShippingEventsTopic   = "shipping.events"
	ConsumerGroupID       = "shipping-service"

	// Dead-letter topics: one per consumed topic
	DLTSuffix                = ".DLT"
	ShippingCommandsDLTTopic = ShippingCommandsTopic + DLTSuffix

	// Headers describing where a dead-lettered message came from and why it failed, named (and
	// encoded) as Spring's DeadLetterPublishingRecoverer does it, so a DLT looks the same whichever
	// service wrote it.
	DLTHeaderPrefix                = "kafka_dlt-"
	DLTOriginalTopicHeader         = DLTHeaderPrefix + "original-topic"
	DLTOriginalPartitionHeader     = DLTHeaderPrefix + "original-partition"
	DLTOriginalOffsetHeader        = DLTHeaderPrefix + "original-offset"
	DLTOriginalConsumerGroupHeader = DLTHeaderPrefix + "original-consumer-group"
	DLTExceptionFQCNHeader         = DLTHeaderPrefix + "exception-fqcn"
	DLTExceptionMessageHeader      = DLTHeaderPrefix + "exception-message"
)

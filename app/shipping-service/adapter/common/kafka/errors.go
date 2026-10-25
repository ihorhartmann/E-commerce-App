package kafka

import "shippingservice/domain/apperror"

// ErrUnmappedShipmentStatus means a shipment status has no Kafka message type to announce it with - a
// bug: every status a shipment can reach needs one.
var ErrUnmappedShipmentStatus = apperror.New("UNMAPPED_SHIPMENT_STATUS", apperror.CategoryInternal, "Internal server error")

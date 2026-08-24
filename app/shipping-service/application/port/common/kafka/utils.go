package kafka

import "strconv"

// FormatKafkaId formats an entity id for use on the wire - as a Kafka message
// correlation id or as a partition key. What the formatted value represents in a
// given call is conveyed by the caller's variable name, not by this function.
func FormatKafkaId(id uint64) string {
	return strconv.FormatUint(id, 10)
}

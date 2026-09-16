package com.hartmann.application.port.common.kafka;

public final class KafkaUtils {
    private KafkaUtils() {
    }

    // formatKafkaId formats an entity id for use on the wire - as a Kafka message
    // correlation id or as a partition key. What the formatted value represents in a
    // given call is conveyed by the caller's variable name, not by this method.
    public static String formatKafkaId(long id) {
        return String.valueOf(id);
    }
}

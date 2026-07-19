package com.hartmann.adapter.common.kafka;

public final class KafkaConst {
    // Header keys
    public static final String EVENT_TYPE = "eventType";
    public static final String CORRELATION_ID = "correlationId";
    public static final String EVENT_ID = "eventId";

    // Topics
    public static final String PAYMENT_EVENTS_TOPIC = "payment.events";
    public static final String PAYMENT_COMMANDS_TOPIC = "payment.commands";

    // Event types
    public static final String PAYMENT_COMPLETED_EVENT_TYPE = "PaymentCompleted";
    public static final String PAYMENT_FAILED_EVENT_TYPE = "PaymentFailed";
    public static final String REFUND_REQUESTED_EVENT_TYPE = "RefundRequested";
    public static final String REFUND_COMPLETED_EVENT_TYPE = "RefundCompleted";

    private KafkaConst() {
    }
}

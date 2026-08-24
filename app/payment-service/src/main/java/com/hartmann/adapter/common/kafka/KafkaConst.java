package com.hartmann.adapter.common.kafka;

public final class KafkaConst {
    // Header keys
    public static final String MESSAGE_TYPE = "messageType";
    public static final String CORRELATION_ID = "correlationId";
    public static final String MESSAGE_ID = "messageId";

    // Topics
    public static final String PAYMENT_EVENTS_TOPIC = "payment.events";
    public static final String PAYMENT_COMMANDS_TOPIC = "payment.commands";

    // Message types
    public static final String PAYMENT_COMPLETED_EVENT = "PaymentCompleted";
    public static final String PAYMENT_FAILED_EVENT = "PaymentFailed";
    public static final String REFUND_REQUESTED_COMMAND = "RefundRequested";
    public static final String REFUND_COMPLETED_EVENT = "RefundCompleted";
    public static final String REFUND_FAILED_EVENT = "RefundFailed";

    private KafkaConst() {
    }
}

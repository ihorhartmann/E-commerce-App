package com.hartmann.application.port.in;

import com.hartmann.application.port.out.idempotency.dto.KafkaMessageMetadata;
import com.hartmann.domain.model.Refund;
import com.hartmann.domain.model.RefundStatus;
import com.hartmann.domain.value.Money;
import java.util.List;
import org.jspecify.annotations.Nullable;

public interface RefundService {
    List<Refund> getByOrderId(long orderId);

    /**
     * Creates and submits a refund. message is only non-null for the Kafka-triggered path
     * (RefundRequestedCommand): when present, message.messageId() is marked processed atomically
     * with creating the INITIATED refund row, so a redelivered command doesn't submit a duplicate
     * refund to the gateway. Pass null when called from the HTTP path, where the Idempotency-Key
     * header already protects against retries. Returns null only when message was non-null and
     * already processed - the caller should treat that as a no-op, not an error.
     */
    Refund submitRefund(@Nullable KafkaMessageMetadata message, long paymentId, Money amount);

    Refund updateStatusByExternalId(String externalId, RefundStatus status);
}

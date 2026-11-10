package com.hartmann.application.port.out.kafka;

import com.hartmann.domain.value.Money;

public interface PaymentKafkaProducer {
    void publishPaymentCompleted(String correlationId, long orderId);

    void publishPaymentExpired(String correlationId, long orderId);

    void publishPaymentCancelled(String correlationId, long orderId);

    void publishRefundCompleted(String correlationId, long orderId, Money amount);

    void publishRefundFailed(String correlationId, long orderId);
}

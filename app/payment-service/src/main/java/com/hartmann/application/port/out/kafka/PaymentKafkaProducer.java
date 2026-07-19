package com.hartmann.application.port.out.kafka;

import com.hartmann.domain.value.Money;

public interface PaymentKafkaProducer {
    void publishPaymentCompleted(long orderId);

    void publishPaymentFailed(long orderId);

    void publishRefundCompleted(long orderId, Money amount);

    void publishRefundFailed(long orderId);
}

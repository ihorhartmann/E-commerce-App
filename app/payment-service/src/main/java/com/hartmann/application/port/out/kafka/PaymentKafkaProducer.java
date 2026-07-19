package com.hartmann.application.port.out.kafka;

import com.hartmann.domain.value.Money;

public interface PaymentKafkaProducer {
    void publishPaymentCompleted(long orderId, long paymentId);

    void publishPaymentFailed(long orderId, long paymentId);

    void publishRefundCompleted(long orderId, long paymentId, Money amount);
}

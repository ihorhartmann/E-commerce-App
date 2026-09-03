package com.hartmann.adapter.in.scheduled;

import com.hartmann.application.port.in.PaymentService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentExpirationSweep {
    private final PaymentService paymentService;

    public PaymentExpirationSweep(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Scheduled(fixedDelay = 300_000)
    public void expireOverduePayments() {
        paymentService.expireOverduePayments();
    }
}

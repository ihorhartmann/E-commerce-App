package com.hartmann.adapter.in.scheduled;

import com.hartmann.application.port.in.PaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class PaymentExpirationSweepTest {

    @Mock
    private PaymentService paymentService;

    @Test
    void expireOverduePayments_delegatesToThePaymentService() {
        var sweep = new PaymentExpirationSweep(paymentService);

        sweep.expireOverduePayments();

        verify(paymentService, times(1)).expireOverduePayments();
    }
}

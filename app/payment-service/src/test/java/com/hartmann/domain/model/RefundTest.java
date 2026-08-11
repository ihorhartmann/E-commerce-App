package com.hartmann.domain.model;

import com.hartmann.domain.exception.DomainException;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundTest {

    private Money testAmount() {
        return new Money(new BigDecimal("10.00"), Currency.USD);
    }

    @Test
    void createsARefundWithTheGivenFields() {
        var now = Instant.now();
        var refund = new Refund(1L, 100L, "re_test", testAmount(), RefundStatus.PENDING, now, now);

        assertThat(refund.paymentId()).isEqualTo(100L);
        assertThat(refund.status()).isEqualTo(RefundStatus.PENDING);
    }

    @Test
    void allowsANullExternalId() {
        var now = Instant.now();
        var refund = new Refund(1L, 100L, null, testAmount(), RefundStatus.PENDING, now, now);

        assertThat(refund.externalId()).isNull();
    }

    @Test
    void rejectsANegativeId() {
        var now = Instant.now();
        assertThatThrownBy(() -> new Refund(-1L, 100L, "re_test", testAmount(), RefundStatus.PENDING, now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsANonPositivePaymentId() {
        var now = Instant.now();
        assertThatThrownBy(() -> new Refund(1L, 0L, "re_test", testAmount(), RefundStatus.PENDING, now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsANullAmount() {
        var now = Instant.now();
        assertThatThrownBy(() -> new Refund(1L, 100L, "re_test", null, RefundStatus.PENDING, now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsANullStatus() {
        var now = Instant.now();
        assertThatThrownBy(() -> new Refund(1L, 100L, "re_test", testAmount(), null, now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNullTimestamps() {
        var now = Instant.now();
        assertThatThrownBy(() -> new Refund(1L, 100L, "re_test", testAmount(), RefundStatus.PENDING, null, now))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new Refund(1L, 100L, "re_test", testAmount(), RefundStatus.PENDING, now, null))
                .isInstanceOf(DomainException.class);
    }
}

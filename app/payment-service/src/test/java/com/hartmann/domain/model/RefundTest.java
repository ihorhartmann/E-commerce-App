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
    void constructor_createsARefundWithTheGivenFields() {
        var now = Instant.now();
        var refund = Refund.createExisting(1L, 100L, "re_test", testAmount(), RefundStatus.PENDING, now, now);

        assertThat(refund.paymentId()).isEqualTo(100L);
        assertThat(refund.status()).isEqualTo(RefundStatus.PENDING);
    }

    @Test
    void constructor_allowsANullExternalId() {
        var now = Instant.now();
        var refund = Refund.createExisting(1L, 100L, null, testAmount(), RefundStatus.PENDING, now, now);

        assertThat(refund.externalId()).isNull();
    }

    @Test
    void constructor_rejectsANonPositivePaymentId() {
        var now = Instant.now();
        assertThatThrownBy(() -> Refund.createExisting(1L, 0L, "re_test", testAmount(), RefundStatus.PENDING, now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void constructor_rejectsANullAmount() {
        var now = Instant.now();
        assertThatThrownBy(() -> Refund.createExisting(1L, 100L, "re_test", null, RefundStatus.PENDING, now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void constructor_rejectsANullStatus() {
        var now = Instant.now();
        assertThatThrownBy(() -> Refund.createExisting(1L, 100L, "re_test", testAmount(), null, now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void constructor_rejectsNullCreatedAt() {
        var now = Instant.now();
        var id = 1L;
        var paymentId = 100L;
        var externalId = "re_test";
        var amount = testAmount();
        var status = RefundStatus.PENDING;
        assertThatThrownBy(() -> Refund.createExisting(id, paymentId, externalId, amount, status, null, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void constructor_allowsNullUpdatedAt() {
        var now = Instant.now();
        var refund = Refund.createExisting(1L, 100L, "re_test", testAmount(), RefundStatus.PENDING, now, null);

        assertThat(refund.updatedAt()).isNull();
    }

    // --- createNew ---

    @Test
    void createNew_buildsARefundWithNoIdYet() {
        var paymentId = 100L;
        var externalId = "re_test";
        var status = RefundStatus.PENDING;
        var refund = Refund.createNew(paymentId, externalId, testAmount(), status);

        assertThat(refund.id()).isZero();
        assertThat(refund.paymentId()).isEqualTo(paymentId);
        assertThat(refund.externalId()).isEqualTo(externalId);
        assertThat(refund.status()).isEqualTo(status);
    }

    // --- createExisting ---

    @Test
    void createExisting_usesTheGivenId() {
        var now = Instant.now();
        var id = 1L;
        var refund = Refund.createExisting(id, 100L, "re_test", testAmount(), RefundStatus.PENDING, now, now);

        assertThat(refund.id()).isEqualTo(id);
    }

    @Test
    void createExisting_throwsWhenIdIsNotPositive() {
        var now = Instant.now();
        assertThatThrownBy(() -> Refund.createExisting(0L, 100L, "re_test", testAmount(), RefundStatus.PENDING, now, now))
                .isInstanceOf(DomainException.class);
    }

    // --- equals ---

    @Test
    void equals_returnsTrueForRefundsWithTheSameId() {
        var now = Instant.now();
        var id = 1L;
        var a = Refund.createExisting(id, 100L, "re_a", testAmount(), RefundStatus.PENDING, now, now);
        var b = Refund.createExisting(id, 200L, "re_b", testAmount(), RefundStatus.SUCCEEDED, now, now);

        assertThat(a).isEqualTo(b);
    }

    @Test
    void equals_returnsFalseForRefundsWithDifferentIds() {
        var now = Instant.now();
        var a = Refund.createExisting(1L, 100L, "re_test", testAmount(), RefundStatus.PENDING, now, now);
        var b = Refund.createExisting(2L, 100L, "re_test", testAmount(), RefundStatus.PENDING, now, now);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void equals_returnsFalseWhenBothRefundsAreNotYetPersisted() {
        var paymentId = 100L;
        var externalId = "re_test";
        var status = RefundStatus.PENDING;
        var a = Refund.createNew(paymentId, externalId, testAmount(), status);
        var b = Refund.createNew(paymentId, externalId, testAmount(), status);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void equals_returnsFalseForAnObjectOfADifferentType() {
        var now = Instant.now();
        var refund = Refund.createExisting(1L, 100L, "re_test", testAmount(), RefundStatus.PENDING, now, now);

        assertThat(refund).isNotEqualTo("not a refund");
    }
}

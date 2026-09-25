package com.hartmann.domain.model;

import com.hartmann.domain.exception.DomainException;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class RefundTest {

    private Money testAmount() {
        return new Money(new BigDecimal("10.00"), Currency.USD);
    }

    @Test
    void constructor_createsARefundWithTheGivenFields() {
        var now = Instant.now();
        var idempotencyKey = UUID.randomUUID();
        var refund = Refund.createExisting(1L, 100L, idempotencyKey, "re_test", testAmount(), RefundStatus.PENDING, now, now);

        assertThat(refund.paymentId()).isEqualTo(100L);
        assertThat(refund.idempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(refund.status()).isEqualTo(RefundStatus.PENDING);
    }

    @Test
    void constructor_allowsANullExternalId() {
        var now = Instant.now();
        var refund = Refund.createExisting(1L, 100L, UUID.randomUUID(), null, testAmount(), RefundStatus.PENDING, now, now);

        assertThat(refund.externalId()).isNull();
    }

    @Test
    void constructor_rejectsANonPositivePaymentId() {
        var now = Instant.now();
        var idempotencyKey = UUID.randomUUID();
        assertThatThrownBy(() -> Refund.createExisting(1L, 0L, idempotencyKey, "re_test", testAmount(), RefundStatus.PENDING, now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void constructor_rejectsANullIdempotencyKey() {
        var now = Instant.now();
        assertThatThrownBy(() -> Refund.createExisting(1L, 100L, null, "re_test", testAmount(), RefundStatus.PENDING, now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void constructor_rejectsANullAmount() {
        var now = Instant.now();
        var idempotencyKey = UUID.randomUUID();
        assertThatThrownBy(() -> Refund.createExisting(1L, 100L, idempotencyKey, "re_test", null, RefundStatus.PENDING, now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void constructor_rejectsANullStatus() {
        var now = Instant.now();
        var idempotencyKey = UUID.randomUUID();
        assertThatThrownBy(() -> Refund.createExisting(1L, 100L, idempotencyKey, "re_test", testAmount(), null, now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void constructor_rejectsNullCreatedAt() {
        var now = Instant.now();
        var id = 1L;
        var paymentId = 100L;
        var idempotencyKey = UUID.randomUUID();
        var externalId = "re_test";
        var amount = testAmount();
        var status = RefundStatus.PENDING;
        assertThatThrownBy(() -> Refund.createExisting(id, paymentId, idempotencyKey, externalId, amount, status, null, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void constructor_allowsNullUpdatedAt() {
        var now = Instant.now();
        var refund = Refund.createExisting(1L, 100L, UUID.randomUUID(), "re_test", testAmount(), RefundStatus.PENDING, now, null);

        assertThat(refund.updatedAt()).isNull();
    }

    // --- createNew ---

    @Test
    void createNew_buildsARefundWithNoIdYet() {
        var paymentId = 100L;
        var idempotencyKey = UUID.randomUUID();
        var externalId = "re_test";
        var status = RefundStatus.PENDING;
        var refund = Refund.createNew(paymentId, idempotencyKey, externalId, testAmount(), status);

        assertThat(refund.id()).isZero();
        assertThat(refund.paymentId()).isEqualTo(paymentId);
        assertThat(refund.idempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(refund.externalId()).isEqualTo(externalId);
        assertThat(refund.status()).isEqualTo(status);
    }

    // --- createExisting ---

    @Test
    void createExisting_usesTheGivenId() {
        var now = Instant.now();
        var id = 1L;
        var refund = Refund.createExisting(id, 100L, UUID.randomUUID(), "re_test", testAmount(), RefundStatus.PENDING, now, now);

        assertThat(refund.id()).isEqualTo(id);
    }

    @Test
    void createExisting_throwsWhenIdIsNotPositive() {
        var now = Instant.now();
        var idempotencyKey = UUID.randomUUID();
        assertThatThrownBy(() -> Refund.createExisting(0L, 100L, idempotencyKey, "re_test", testAmount(), RefundStatus.PENDING, now, now))
                .isInstanceOf(DomainException.class);
    }

    // --- matches ---

    @Test
    void matchesPaymentIdAndAmount_returnsTrueForTheSamePaymentAndAmount() {
        var paymentId = 100L;
        var refund = Refund.createNew(paymentId, UUID.randomUUID(), null, testAmount(), RefundStatus.INITIATED);

        assertThat(refund.matchesPaymentIdAndAmount(paymentId, testAmount())).isTrue();
    }

    @Test
    void matchesPaymentIdAndAmount_ignoresTheScaleOfTheAmount() {
        var paymentId = 100L;
        var refund = Refund.createNew(paymentId, UUID.randomUUID(), null, testAmount(), RefundStatus.INITIATED);

        assertThat(refund.matchesPaymentIdAndAmount(paymentId, new Money(new BigDecimal("10"), Currency.USD))).isTrue();
    }

    @Test
    void matchesPaymentIdAndAmount_returnsFalseForADifferentPayment() {
        var refund = Refund.createNew(100L, UUID.randomUUID(), null, testAmount(), RefundStatus.INITIATED);

        assertThat(refund.matchesPaymentIdAndAmount(200L, testAmount())).isFalse();
    }

    @Test
    void matchesPaymentIdAndAmount_returnsFalseForADifferentAmount() {
        var paymentId = 100L;
        var refund = Refund.createNew(paymentId, UUID.randomUUID(), null, testAmount(), RefundStatus.INITIATED);

        assertThat(refund.matchesPaymentIdAndAmount(paymentId, new Money(new BigDecimal("10.01"), Currency.USD))).isFalse();
    }

    @Test
    void matchesPaymentIdAndAmount_returnsFalseForADifferentCurrency() {
        var paymentId = 100L;
        var refund = Refund.createNew(paymentId, UUID.randomUUID(), null, testAmount(), RefundStatus.INITIATED);

        assertThat(refund.matchesPaymentIdAndAmount(paymentId, new Money(new BigDecimal("10.00"), Currency.EUR))).isFalse();
    }

    // --- equals ---

    @Test
    void equals_returnsTrueForRefundsWithTheSameId() {
        var now = Instant.now();
        var id = 1L;
        var a = Refund.createExisting(id, 100L, UUID.randomUUID(), "re_a", testAmount(), RefundStatus.PENDING, now, now);
        var b = Refund.createExisting(id, 200L, UUID.randomUUID(), "re_b", testAmount(), RefundStatus.SUCCEEDED, now, now);

        assertThat(a).isEqualTo(b);
    }

    @Test
    void equals_returnsFalseForRefundsWithDifferentIds() {
        var now = Instant.now();
        var idempotencyKey = UUID.randomUUID();
        var a = Refund.createExisting(1L, 100L, idempotencyKey, "re_test", testAmount(), RefundStatus.PENDING, now, now);
        var b = Refund.createExisting(2L, 100L, idempotencyKey, "re_test", testAmount(), RefundStatus.PENDING, now, now);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void equals_returnsFalseWhenBothRefundsAreNotYetPersisted() {
        var paymentId = 100L;
        var idempotencyKey = UUID.randomUUID();
        var externalId = "re_test";
        var status = RefundStatus.PENDING;
        var a = Refund.createNew(paymentId, idempotencyKey, externalId, testAmount(), status);
        var b = Refund.createNew(paymentId, idempotencyKey, externalId, testAmount(), status);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void equals_returnsFalseForAnObjectOfADifferentType() {
        var now = Instant.now();
        var refund = Refund.createExisting(1L, 100L, UUID.randomUUID(), "re_test", testAmount(), RefundStatus.PENDING, now, now);

        assertThat(refund).isNotEqualTo("not a refund");
    }
}

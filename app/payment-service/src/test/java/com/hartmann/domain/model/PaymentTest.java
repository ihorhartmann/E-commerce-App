package com.hartmann.domain.model;

import com.hartmann.domain.exception.DomainException;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private Money money(String amount, Currency currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    private Payment testPayment(PaymentStatus status, String amount, List<Refund> refunds) {
        var now = Instant.now();
        return Payment.createExisting(1L, 100L, "pi_test", money(amount, Currency.USD), status, refunds, now, now);
    }

    private Refund testRefund(String amount, RefundStatus status) {
        var now = Instant.now();
        return Refund.createExisting(1L, 1L, "re_test", money(amount, Currency.USD), status, now, now);
    }

    // --- constructor validation ---

    @Test
    void constructor_createsAPaymentWithTheGivenFields() {
        var payment = testPayment(PaymentStatus.COMPLETED, "100.00", List.of());

        assertThat(payment.orderId()).isEqualTo(100L);
        assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.refunds()).isEmpty();
    }

    @Test
    void constructor_rejectsANonPositiveOrderId() {
        var now = Instant.now();
        assertThatThrownBy(() -> Payment.createExisting(1L, 0L, "pi_test", money("10.00", Currency.USD),
                PaymentStatus.COMPLETED, List.of(), now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void constructor_rejectsANullExternalId() {
        var now = Instant.now();
        assertThatThrownBy(() -> Payment.createExisting(1L, 100L, null, money("10.00", Currency.USD),
                PaymentStatus.COMPLETED, List.of(), now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void constructor_rejectsABlankExternalId() {
        var now = Instant.now();
        assertThatThrownBy(() -> Payment.createExisting(1L, 100L, "  ", money("10.00", Currency.USD),
                PaymentStatus.COMPLETED, List.of(), now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void constructor_rejectsANullAmount() {
        var now = Instant.now();
        assertThatThrownBy(() -> Payment.createExisting(1L, 100L, "pi_test", null,
                PaymentStatus.COMPLETED, List.of(), now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void constructor_rejectsANullStatus() {
        var now = Instant.now();
        assertThatThrownBy(() -> Payment.createExisting(1L, 100L, "pi_test", money("10.00", Currency.USD),
                null, List.of(), now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void constructor_rejectsANullRefundsList() {
        var now = Instant.now();
        assertThatThrownBy(() -> Payment.createExisting(1L, 100L, "pi_test", money("10.00", Currency.USD),
                PaymentStatus.COMPLETED, null, now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void constructor_rejectsNullCreatedAt() {
        var now = Instant.now();
        var id = 1L;
        var orderId = 100L;
        var externalId = "pi_test";
        var amount = money("10.00", Currency.USD);
        var status = PaymentStatus.COMPLETED;
        var refunds = List.<Refund>of();
        assertThatThrownBy(() -> Payment.createExisting(id, orderId, externalId, amount, status, refunds, null, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void constructor_allowsNullUpdatedAt() {
        var now = Instant.now();
        var payment = Payment.createExisting(1L, 100L, "pi_test", money("10.00", Currency.USD),
                PaymentStatus.COMPLETED, List.of(), now, null);

        assertThat(payment.updatedAt()).isNull();
    }

    // --- createNew ---

    @Test
    void createNew_buildsAPendingPaymentWithNoRefundsAndNoIdYet() {
        var orderId = 100L;
        var payment = Payment.createNew(orderId, "pi_test", money("10.00", Currency.USD));

        assertThat(payment.id()).isZero();
        assertThat(payment.orderId()).isEqualTo(orderId);
        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.refunds()).isEmpty();
    }

    // --- createExisting ---

    @Test
    void createExisting_usesTheGivenId() {
        var payment = testPayment(PaymentStatus.COMPLETED, "100.00", List.of());

        assertThat(payment.id()).isEqualTo(1L);
    }

    @Test
    void createExisting_throwsWhenIdIsNotPositive() {
        var now = Instant.now();
        assertThatThrownBy(() -> Payment.createExisting(0L, 100L, "pi_test", money("10.00", Currency.USD),
                PaymentStatus.COMPLETED, List.of(), now, now))
                .isInstanceOf(DomainException.class);
    }

    // --- createRefund ---

    @Test
    void createRefund_throwsWhenPaymentIsNotCompleted() {
        var payment = testPayment(PaymentStatus.PENDING, "100.00", List.of());

        assertThatThrownBy(() -> payment.createRefund(money("10.00", Currency.USD)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void createRefund_throwsOnCurrencyMismatch() {
        var payment = testPayment(PaymentStatus.COMPLETED, "100.00", List.of());

        assertThatThrownBy(() -> payment.createRefund(money("10.00", Currency.EUR)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void createRefund_buildsAPendingRefundForTheRequestedAmount() {
        var amount = "40.00";
        var payment = testPayment(PaymentStatus.COMPLETED, "100.00", List.of());

        var refund = payment.createRefund(money(amount, Currency.USD));

        assertThat(refund.paymentId()).isEqualTo(payment.id());
        assertThat(refund.amount().amount()).isEqualByComparingTo(amount);
        assertThat(refund.status()).isEqualTo(RefundStatus.PENDING);
        assertThat(refund.externalId()).isNull();
    }

    @Test
    void createRefund_allowsRequestingExactlyTheFullBalanceWithNoExistingRefunds() {
        var payment = testPayment(PaymentStatus.COMPLETED, "100.00", List.of());

        assertThat(payment.createRefund(money("100.00", Currency.USD))).isNotNull();
    }

    @Test
    void createRefund_throwsWhenRequestingMoreThanTheFullBalance() {
        var payment = testPayment(PaymentStatus.COMPLETED, "100.00", List.of());

        assertThatThrownBy(() -> payment.createRefund(money("100.01", Currency.USD)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void createRefund_subtractsPendingAndSucceededRefundsFromTheRefundableBalance() {
        var payment = testPayment(PaymentStatus.COMPLETED, "100.00",
                List.of(testRefund("40.00", RefundStatus.PENDING)));

        assertThat(payment.createRefund(money("60.00", Currency.USD))).isNotNull();
        assertThatThrownBy(() -> payment.createRefund(money("60.01", Currency.USD)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void createRefund_doesNotCountFailedRefundsAgainstTheRefundableBalance() {
        var payment = testPayment(PaymentStatus.COMPLETED, "100.00",
                List.of(testRefund("40.00", RefundStatus.FAILED)));

        assertThat(payment.createRefund(money("100.00", Currency.USD))).isNotNull();
    }

    // --- equals ---

    @Test
    void equals_returnsTrueForPaymentsWithTheSameId() {
        var now = Instant.now();
        var id = 1L;
        var a = Payment.createExisting(id, 100L, "pi_a", money("10.00", Currency.USD), PaymentStatus.PENDING, List.of(), now, now);
        var b = Payment.createExisting(id, 200L, "pi_b", money("20.00", Currency.EUR), PaymentStatus.COMPLETED, List.of(), now, now);

        assertThat(a).isEqualTo(b);
    }

    @Test
    void equals_returnsFalseForPaymentsWithDifferentIds() {
        var status = PaymentStatus.COMPLETED;
        var amount = "100.00";
        var payment1 = testPayment(status, amount, List.of());
        var now = Instant.now();
        var payment2 = Payment.createExisting(2L, 100L, "pi_test", money(amount, Currency.USD), status, List.of(), now, now);

        assertThat(payment1).isNotEqualTo(payment2);
    }

    @Test
    void equals_returnsFalseWhenBothPaymentsAreNotYetPersisted() {
        var orderId = 100L;
        var externalId = "pi_a";
        var amount = money("10.00", Currency.USD);
        var a = Payment.createNew(orderId, externalId, amount);
        var b = Payment.createNew(orderId, externalId, amount);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void equals_returnsFalseForAnObjectOfADifferentType() {
        var payment = testPayment(PaymentStatus.COMPLETED, "100.00", List.of());

        assertThat(payment).isNotEqualTo("not a payment");
    }
}

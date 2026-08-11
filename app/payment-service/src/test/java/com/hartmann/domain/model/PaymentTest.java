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
        return new Payment(1L, 100L, "pi_test", money(amount, Currency.USD), status, refunds, now, now);
    }

    private Refund testRefund(String amount, RefundStatus status) {
        var now = Instant.now();
        return new Refund(1L, 1L, "re_test", money(amount, Currency.USD), status, now, now);
    }

    // --- compact constructor validation ---

    @Test
    void createsAPaymentWithTheGivenFields() {
        var payment = testPayment(PaymentStatus.COMPLETED, "100.00", List.of());

        assertThat(payment.orderId()).isEqualTo(100L);
        assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.refunds()).isEmpty();
    }

    @Test
    void rejectsANegativeId() {
        var now = Instant.now();
        assertThatThrownBy(() -> new Payment(-1L, 100L, "pi_test", money("10.00", Currency.USD),
                PaymentStatus.COMPLETED, List.of(), now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsANonPositiveOrderId() {
        var now = Instant.now();
        assertThatThrownBy(() -> new Payment(1L, 0L, "pi_test", money("10.00", Currency.USD),
                PaymentStatus.COMPLETED, List.of(), now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsANullExternalId() {
        var now = Instant.now();
        assertThatThrownBy(() -> new Payment(1L, 100L, null, money("10.00", Currency.USD),
                PaymentStatus.COMPLETED, List.of(), now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsABlankExternalId() {
        var now = Instant.now();
        assertThatThrownBy(() -> new Payment(1L, 100L, "  ", money("10.00", Currency.USD),
                PaymentStatus.COMPLETED, List.of(), now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsANullAmount() {
        var now = Instant.now();
        assertThatThrownBy(() -> new Payment(1L, 100L, "pi_test", null,
                PaymentStatus.COMPLETED, List.of(), now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsANullStatus() {
        var now = Instant.now();
        assertThatThrownBy(() -> new Payment(1L, 100L, "pi_test", money("10.00", Currency.USD),
                null, List.of(), now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsANullRefundsList() {
        var now = Instant.now();
        assertThatThrownBy(() -> new Payment(1L, 100L, "pi_test", money("10.00", Currency.USD),
                PaymentStatus.COMPLETED, null, now, now))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNullTimestamps() {
        var now = Instant.now();
        assertThatThrownBy(() -> new Payment(1L, 100L, "pi_test", money("10.00", Currency.USD),
                PaymentStatus.COMPLETED, List.of(), null, now))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new Payment(1L, 100L, "pi_test", money("10.00", Currency.USD),
                PaymentStatus.COMPLETED, List.of(), now, null))
                .isInstanceOf(DomainException.class);
    }

    // --- createRefund ---

    @Test
    void createRefundThrowsWhenPaymentIsNotCompleted() {
        var payment = testPayment(PaymentStatus.PENDING, "100.00", List.of());

        assertThatThrownBy(() -> payment.createRefund(money("10.00", Currency.USD)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void createRefundThrowsOnCurrencyMismatch() {
        var payment = testPayment(PaymentStatus.COMPLETED, "100.00", List.of());

        assertThatThrownBy(() -> payment.createRefund(money("10.00", Currency.EUR)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void createRefundBuildsAPendingRefundForTheRequestedAmount() {
        var payment = testPayment(PaymentStatus.COMPLETED, "100.00", List.of());

        var refund = payment.createRefund(money("40.00", Currency.USD));

        assertThat(refund.paymentId()).isEqualTo(payment.id());
        assertThat(refund.amount().amount()).isEqualByComparingTo("40.00");
        assertThat(refund.status()).isEqualTo(RefundStatus.PENDING);
        assertThat(refund.externalId()).isNull();
    }

    @Test
    void createRefundAllowsRequestingExactlyTheFullBalanceWithNoExistingRefunds() {
        var payment = testPayment(PaymentStatus.COMPLETED, "100.00", List.of());

        assertThat(payment.createRefund(money("100.00", Currency.USD))).isNotNull();
    }

    @Test
    void createRefundThrowsWhenRequestingMoreThanTheFullBalance() {
        var payment = testPayment(PaymentStatus.COMPLETED, "100.00", List.of());

        assertThatThrownBy(() -> payment.createRefund(money("100.01", Currency.USD)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void createRefundSubtractsPendingAndSucceededRefundsFromTheRefundableBalance() {
        var payment = testPayment(PaymentStatus.COMPLETED, "100.00",
                List.of(testRefund("40.00", RefundStatus.PENDING)));

        assertThat(payment.createRefund(money("60.00", Currency.USD))).isNotNull();
        assertThatThrownBy(() -> payment.createRefund(money("60.01", Currency.USD)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void createRefundDoesNotCountFailedRefundsAgainstTheRefundableBalance() {
        var payment = testPayment(PaymentStatus.COMPLETED, "100.00",
                List.of(testRefund("40.00", RefundStatus.FAILED)));

        assertThat(payment.createRefund(money("100.00", Currency.USD))).isNotNull();
    }
}

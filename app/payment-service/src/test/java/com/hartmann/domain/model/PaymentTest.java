package com.hartmann.domain.model;

import com.hartmann.domain.model.exception.InconsistentPaymentException;
import com.hartmann.domain.model.exception.InvalidPaymentOrderIdException;
import com.hartmann.domain.model.exception.PaymentInvariantViolatedException;
import com.hartmann.domain.model.exception.PaymentNotRefundableException;
import com.hartmann.domain.model.exception.PaymentTransitionNotAllowedException;
import com.hartmann.domain.model.exception.RefundExceedsBalanceException;
import com.hartmann.domain.value.Currency;
import com.hartmann.domain.value.Money;
import com.hartmann.domain.value.exception.CurrencyMismatchException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class PaymentTest {
    private static final long EXPIRES_IN_SECONDS = 900;

    private Money money(String amount, Currency currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    private Payment testPayment(PaymentStatus status, String amount, String refundedAmount) {
        var now = Instant.now();
        return Payment.createExisting(1L, 100L, "pi_test", money(amount, Currency.USD), status,
                money(refundedAmount, Currency.USD), 0L, now, now, now.plusSeconds(EXPIRES_IN_SECONDS));
    }

    private Payment testInitiatedPayment() {
        var now = Instant.now();
        return Payment.createExisting(1L, 100L, null, money("100.00", Currency.USD), PaymentStatus.INITIATED, money("0.00", Currency.USD), 0L, now, null, now.plusSeconds(EXPIRES_IN_SECONDS));
    }

    private Refund testRefund(long paymentId, String amount, RefundStatus status) {
        var now = Instant.now();
        return Refund.createExisting(1L, paymentId, UUID.randomUUID(), "re_test", money(amount, Currency.USD), status, 0L, now, now);
    }

    // --- constructor validation ---

    @Test
    void createExisting_createsAPaymentWithTheGivenFields() {
        var payment = testPayment(PaymentStatus.CONFIRMED, "100.00", "0.00");

        assertThat(payment.orderId()).isEqualTo(100L);
        assertThat(payment.status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(payment.refundedAmount()).isEqualTo(money("0.00", Currency.USD));
    }

    @Test
    void createExisting_rejectsANonPositiveOrderId() {
        var now = Instant.now();
        assertThatThrownBy(() -> Payment.createExisting(1L, 0L, "pi_test", money("10.00", Currency.USD),
                PaymentStatus.CONFIRMED, money("0.00", Currency.USD), 0L, now, now, now.plusSeconds(EXPIRES_IN_SECONDS)))
                .isInstanceOf(InconsistentPaymentException.class);
    }

    @Test
    void createExisting_rejectsANullExternalIdWhenNotInitiated() {
        var now = Instant.now();
        assertThatThrownBy(() -> Payment.createExisting(1L, 100L, null, money("10.00", Currency.USD),
                PaymentStatus.CONFIRMED, money("0.00", Currency.USD), 0L, now, now, now.plusSeconds(EXPIRES_IN_SECONDS)))
                .isInstanceOf(InconsistentPaymentException.class);
    }

    @Test
    void createExisting_rejectsABlankExternalIdWhenNotInitiated() {
        var now = Instant.now();
        assertThatThrownBy(() -> Payment.createExisting(1L, 100L, "  ", money("10.00", Currency.USD),
                PaymentStatus.CONFIRMED, money("0.00", Currency.USD), 0L, now, now, now.plusSeconds(EXPIRES_IN_SECONDS)))
                .isInstanceOf(InconsistentPaymentException.class);
    }

    @Test
    void createExisting_allowsANullExternalIdWhenInitiated() {
        var now = Instant.now();
        var payment = Payment.createExisting(1L, 100L, null, money("10.00", Currency.USD),
                PaymentStatus.INITIATED, money("0.00", Currency.USD), 0L, now, now, now.plusSeconds(EXPIRES_IN_SECONDS));

        assertThat(payment.externalId()).isNull();
    }

    @Test
    void createExisting_allowsANullExternalIdWhenExpired() {
        var now = Instant.now();
        var payment = Payment.createExisting(1L, 100L, null, money("10.00", Currency.USD),
                PaymentStatus.EXPIRED, money("0.00", Currency.USD), 0L, now, now, now.plusSeconds(EXPIRES_IN_SECONDS));

        assertThat(payment.externalId()).isNull();
    }

    @Test
    void createExisting_allowsANullExternalIdWhenFailed() {
        var now = Instant.now();
        var payment = Payment.createExisting(1L, 100L, null, money("10.00", Currency.USD),
                PaymentStatus.FAILED, money("0.00", Currency.USD), 0L, now, now, now.plusSeconds(EXPIRES_IN_SECONDS));

        assertThat(payment.externalId()).isNull();
    }

    @Test
    void createExisting_rejectsANullAmount() {
        var now = Instant.now();
        assertThatThrownBy(() -> Payment.createExisting(1L, 100L, "pi_test", null,
                PaymentStatus.CONFIRMED, money("0.00", Currency.USD), 0L, now, now, now.plusSeconds(EXPIRES_IN_SECONDS)))
                .isInstanceOf(InconsistentPaymentException.class);
    }

    @Test
    void createExisting_rejectsANullStatus() {
        var now = Instant.now();
        assertThatThrownBy(() -> Payment.createExisting(1L, 100L, "pi_test", money("10.00", Currency.USD),
                null, money("0.00", Currency.USD), 0L, now, now, now.plusSeconds(EXPIRES_IN_SECONDS)))
                .isInstanceOf(InconsistentPaymentException.class);
    }

    @Test
    void createExisting_rejectsANullRefundedAmount() {
        var now = Instant.now();
        assertThatThrownBy(() -> Payment.createExisting(1L, 100L, "pi_test", money("10.00", Currency.USD),
                PaymentStatus.CONFIRMED, null, 0L, now, now, now.plusSeconds(EXPIRES_IN_SECONDS)))
                .isInstanceOf(InconsistentPaymentException.class);
    }

    @Test
    void createExisting_rejectsARefundedAmountInAnotherCurrency() {
        var now = Instant.now();
        assertThatThrownBy(() -> Payment.createExisting(1L, 100L, "pi_test", money("10.00", Currency.USD),
                PaymentStatus.CONFIRMED, money("0.00", Currency.EUR), 0L, now, now, now.plusSeconds(EXPIRES_IN_SECONDS)))
                .isInstanceOf(InconsistentPaymentException.class);
    }

    @Test
    void createExisting_rejectsARefundedAmountAboveTheAmount() {
        assertThatThrownBy(() -> testPayment(PaymentStatus.CONFIRMED, "10.00", "10.01"))
                .isInstanceOf(InconsistentPaymentException.class);
    }

    @Test
    void createExisting_rejectsNullCreatedAt() {
        var now = Instant.now();
        var id = 1L;
        var orderId = 100L;
        var externalId = "pi_test";
        var amount = money("10.00", Currency.USD);
        var status = PaymentStatus.CONFIRMED;
        var refundedAmount = money("0.00", Currency.USD);
        assertThatThrownBy(() -> Payment.createExisting(id, orderId, externalId, amount, status, refundedAmount, 0L, null, now, now.plusSeconds(EXPIRES_IN_SECONDS)))
                .isInstanceOf(InconsistentPaymentException.class);
    }

    @Test
    void createExisting_rejectsNullExpiresAt() {
        var now = Instant.now();
        var id = 1L;
        var orderId = 100L;
        var externalId = "pi_test";
        var amount = money("10.00", Currency.USD);
        var status = PaymentStatus.CONFIRMED;
        var refundedAmount = money("0.00", Currency.USD);
        assertThatThrownBy(() -> Payment.createExisting(id, orderId, externalId, amount, status, refundedAmount, 0L, now, now, null))
                .isInstanceOf(InconsistentPaymentException.class);
    }

    @Test
    void createExisting_allowsNullUpdatedAt() {
        var now = Instant.now();
        var payment = Payment.createExisting(1L, 100L, "pi_test", money("10.00", Currency.USD),
                PaymentStatus.CONFIRMED, money("0.00", Currency.USD), 0L, now, null, now.plusSeconds(EXPIRES_IN_SECONDS));

        assertThat(payment.updatedAt()).isNull();
    }

    // --- createNew ---

    @Test
    void createNew_buildsAnInitiatedPaymentWithNoExternalIdYetAndAFutureExpiry() {
        var orderId = 100L;
        var payment = Payment.createNew(orderId, money("10.00", Currency.USD));

        assertThat(payment.id()).isZero();
        assertThat(payment.orderId()).isEqualTo(orderId);
        assertThat(payment.status()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(payment.externalId()).isNull();
        assertThat(payment.refundedAmount()).isEqualTo(money("0.00", Currency.USD));
        assertThat(payment.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void createNew_rejectsANonPositiveOrderIdAsAnInvalidRequest() {
        assertThatThrownBy(() -> Payment.createNew(0L, money("10.00", Currency.USD)))
                .isInstanceOf(InvalidPaymentOrderIdException.class);
    }

    @Test
    void createNew_rejectsAMissingAmountAsAViolatedInvariant() {
        assertThatThrownBy(() -> Payment.createNew(100L, null))
                .isInstanceOf(PaymentInvariantViolatedException.class);
    }

    // --- createExisting ---

    @Test
    void createExisting_usesTheGivenId() {
        var payment = testPayment(PaymentStatus.CONFIRMED, "100.00", "0.00");

        assertThat(payment.id()).isEqualTo(1L);
    }

    @Test
    void createExisting_throwsWhenIdIsNotPositive() {
        var now = Instant.now();
        assertThatThrownBy(() -> Payment.createExisting(0L, 100L, "pi_test", money("10.00", Currency.USD),
                PaymentStatus.CONFIRMED, money("0.00", Currency.USD), 0L, now, now, now.plusSeconds(EXPIRES_IN_SECONDS)))
                .isInstanceOf(InconsistentPaymentException.class);
    }

    // --- requestRefund ---

    @Test
    void requestRefund_throwsWhenPaymentIsNotConfirmed() {
        var refundedAmount = "0.00";
        var payment = testPayment(PaymentStatus.PENDING, "100.00", refundedAmount);

        assertThatThrownBy(() -> payment.requestRefund(money("10.00", Currency.USD), UUID.randomUUID()))
                .isInstanceOf(PaymentNotRefundableException.class);
        assertThat(payment.refundedAmount()).isEqualTo(money(refundedAmount, Currency.USD));
    }

    @Test
    void requestRefund_throwsOnCurrencyMismatch() {
        var payment = testPayment(PaymentStatus.CONFIRMED, "100.00", "0.00");

        assertThatThrownBy(() -> payment.requestRefund(money("10.00", Currency.EUR), UUID.randomUUID()))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void requestRefund_buildsAnInitiatedRefundAndAddsItsAmountToTheRefundedAmount() {
        var amount = "40.00";
        var idempotencyKey = UUID.randomUUID();
        var payment = testPayment(PaymentStatus.CONFIRMED, "100.00", "10.00");

        var refund = payment.requestRefund(money(amount, Currency.USD), idempotencyKey);

        assertThat(refund.paymentId()).isEqualTo(payment.id());
        assertThat(refund.idempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(refund.amount()).isEqualTo(money(amount, Currency.USD));
        assertThat(refund.status()).isEqualTo(RefundStatus.INITIATED);
        assertThat(refund.externalId()).isNull();
        assertThat(payment.refundedAmount()).isEqualTo(money("50.00", Currency.USD));
        assertThat(payment.version()).isEqualTo(0L);
    }

    @Test
    void requestRefund_allowsRequestingExactlyTheRefundableBalance() {
        var payment = testPayment(PaymentStatus.CONFIRMED, "100.00", "40.00");

        payment.requestRefund(money("60.00", Currency.USD), UUID.randomUUID());

        assertThat(payment.refundedAmount()).isEqualTo(money("100.00", Currency.USD));
    }

    @Test
    void requestRefund_throwsWhenRequestingMoreThanTheRefundableBalance() {
        var refundedAmount = "40.00";
        var payment = testPayment(PaymentStatus.CONFIRMED, "100.00", refundedAmount);

        assertThatThrownBy(() -> payment.requestRefund(money("60.01", Currency.USD), UUID.randomUUID()))
                .isInstanceOf(RefundExceedsBalanceException.class);
        assertThat(payment.refundedAmount()).isEqualTo(money(refundedAmount, Currency.USD));
    }

    // --- releaseRefund ---

    @Test
    void releaseRefund_subtractsAFailedRefundFromTheRefundedAmount() {
        var payment = testPayment(PaymentStatus.CONFIRMED, "100.00", "70.00");

        payment.releaseRefund(testRefund(payment.id(), "40.00", RefundStatus.FAILED));

        assertThat(payment.refundedAmount()).isEqualTo(money("30.00", Currency.USD));
    }

    @Test
    void releaseRefund_throwsForARefundThatHasNotFailed() {
        var refundedAmount = "70.00";
        var payment = testPayment(PaymentStatus.CONFIRMED, "100.00", refundedAmount);
        var refund = testRefund(payment.id(), "40.00", RefundStatus.PENDING);

        assertThatThrownBy(() -> payment.releaseRefund(refund)).isInstanceOf(PaymentInvariantViolatedException.class);
        assertThat(payment.refundedAmount()).isEqualTo(money(refundedAmount, Currency.USD));
    }

    @Test
    void releaseRefund_throwsForARefundOfAnotherPayment() {
        var payment = testPayment(PaymentStatus.CONFIRMED, "100.00", "70.00");
        var refund = testRefund(payment.id() + 1, "40.00", RefundStatus.FAILED);

        assertThatThrownBy(() -> payment.releaseRefund(refund)).isInstanceOf(PaymentInvariantViolatedException.class);
    }

    // --- wasSubmitted ---

    @Test
    void wasSubmitted_isFalseBeforeTheGatewayCreatedAPaymentIntent() {
        assertThat(testInitiatedPayment().wasSubmitted()).isFalse();
    }

    @Test
    void wasSubmitted_isTrueOnceThePaymentHasAnExternalId() {
        assertThat(testPayment(PaymentStatus.PENDING, "100.00", "0.00").wasSubmitted()).isTrue();
    }

    // --- markPending ---

    @Test
    void markPending_movesAnInitiatedPaymentToPendingWithTheExternalId() {
        var externalId = "pi_new";

        var payment = testInitiatedPayment();

        payment.markPending(externalId);

        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.externalId()).isEqualTo(externalId);
    }

    @Test
    void markPending_throwsWhenThePaymentIsNotInitiated() {
        var payment = testPayment(PaymentStatus.PENDING, "100.00", "0.00");

        assertThatThrownBy(() -> payment.markPending("pi_new")).isInstanceOf(PaymentTransitionNotAllowedException.class);
    }

    @Test
    void markPending_throwsForABlankExternalIdLeavingThePaymentInitiated() {
        var payment = testInitiatedPayment();

        assertThatThrownBy(() -> payment.markPending(" ")).isInstanceOf(PaymentInvariantViolatedException.class);
        assertThat(payment.status()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(payment.externalId()).isNull();
    }

    // --- fail ---

    @Test
    void fail_movesAnInitiatedPaymentToFailed() {
        var payment = testInitiatedPayment();

        payment.fail();

        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void fail_throwsWhenThePaymentIsNotInitiated() {
        var payment = testPayment(PaymentStatus.PENDING, "100.00", "0.00");

        assertThatThrownBy(payment::fail).isInstanceOf(PaymentTransitionNotAllowedException.class);
        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
    }

    // --- applyGatewayStatus ---

    @Test
    void applyGatewayStatus_reportsNoChangeForTheStatusItAlreadyHas() {
        var payment = testPayment(PaymentStatus.CONFIRMED, "100.00", "0.00");
        var updatedAt = payment.updatedAt();

        assertThat(payment.applyGatewayStatus(PaymentStatus.CONFIRMED)).isFalse();
        assertThat(payment.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void applyGatewayStatus_confirmsAPendingOrDeclinedPayment() {
        for (var status : List.of(PaymentStatus.PENDING, PaymentStatus.ATTEMPT_DECLINED)) {
            var payment = testPayment(status, "100.00", "0.00");

            assertThat(payment.applyGatewayStatus(PaymentStatus.CONFIRMED)).isTrue();
            assertThat(payment.status()).isEqualTo(PaymentStatus.CONFIRMED);
        }
    }

    @Test
    void applyGatewayStatus_throwsWhenConfirmingAPaymentThatAlreadyExpiredOrFailed() {
        for (var status : List.of(PaymentStatus.EXPIRED, PaymentStatus.FAILED)) {
            var payment = testPayment(status, "100.00", "0.00");

            assertThatThrownBy(() -> payment.applyGatewayStatus(PaymentStatus.CONFIRMED))
                    .isInstanceOf(PaymentTransitionNotAllowedException.class);
            assertThat(payment.status()).isEqualTo(status);
        }
    }

    @Test
    void applyGatewayStatus_declinesAnAttemptOfAPendingPayment() {
        var payment = testPayment(PaymentStatus.PENDING, "100.00", "0.00");

        assertThat(payment.applyGatewayStatus(PaymentStatus.ATTEMPT_DECLINED)).isTrue();
        assertThat(payment.status()).isEqualTo(PaymentStatus.ATTEMPT_DECLINED);
    }

    @Test
    void applyGatewayStatus_throwsWhenDecliningAnAttemptOfAConfirmedPayment() {
        var payment = testPayment(PaymentStatus.CONFIRMED, "100.00", "0.00");

        assertThatThrownBy(() -> payment.applyGatewayStatus(PaymentStatus.ATTEMPT_DECLINED))
                .isInstanceOf(PaymentTransitionNotAllowedException.class);
    }

    @Test
    void applyGatewayStatus_throwsForAStatusTheGatewayNeverReports() {
        var payment = testPayment(PaymentStatus.PENDING, "100.00", "0.00");

        assertThatThrownBy(() -> payment.applyGatewayStatus(PaymentStatus.EXPIRED))
                .isInstanceOf(PaymentInvariantViolatedException.class);
    }

    // --- equals ---

    @Test
    void equals_returnsTrueForPaymentsWithTheSameId() {
        var now = Instant.now();
        var id = 1L;
        var expiresAt = now.plusSeconds(EXPIRES_IN_SECONDS);
        var a = Payment.createExisting(id, 100L, "pi_a", money("10.00", Currency.USD), PaymentStatus.PENDING, money("0.00", Currency.USD), 0L, now, now, expiresAt);
        var b = Payment.createExisting(id, 200L, "pi_b", money("20.00", Currency.EUR), PaymentStatus.CONFIRMED, money("0.00", Currency.EUR), 0L, now, now, expiresAt);

        assertThat(a).isEqualTo(b);
    }

    @Test
    void equals_returnsFalseForPaymentsWithDifferentIds() {
        var status = PaymentStatus.CONFIRMED;
        var amount = "100.00";
        var payment1 = testPayment(status, amount, "0.00");
        var now = Instant.now();
        var payment2 = Payment.createExisting(2L, 100L, "pi_test", money(amount, Currency.USD), status, money("0.00", Currency.USD), 0L, now, now, now.plusSeconds(EXPIRES_IN_SECONDS));

        assertThat(payment1).isNotEqualTo(payment2);
    }

    @Test
    void equals_returnsFalseWhenBothPaymentsAreNotYetPersisted() {
        var orderId = 100L;
        var amount = money("10.00", Currency.USD);
        var a = Payment.createNew(orderId, amount);
        var b = Payment.createNew(orderId, amount);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void equals_returnsFalseForAnObjectOfADifferentType() {
        var payment = testPayment(PaymentStatus.CONFIRMED, "100.00", "0.00");

        assertThat(payment).isNotEqualTo("not a payment");
    }

    @Test
    void createExisting_allowsANullExternalIdWhenCancelled() {
        var now = Instant.now();
        var payment = Payment.createExisting(1L, 100L, null, money("10.00", Currency.USD),
                PaymentStatus.CANCELLED, money("0.00", Currency.USD), 0L, now, now, now.plusSeconds(EXPIRES_IN_SECONDS));

        assertThat(payment.externalId()).isNull();
    }

    // --- isOpen, isConfirmed ---

    @Test
    void isOpen_isTrueForAPaymentThatCanStillGoAnyWay() {
        for (var status : List.of(PaymentStatus.PENDING, PaymentStatus.ATTEMPT_DECLINED)) {
            assertThat(testPayment(status, "100.00", "0.00").isOpen()).as(status.name()).isTrue();
        }
        assertThat(testInitiatedPayment().isOpen()).isTrue();
    }

    @Test
    void isOpen_isFalseOnceThePaymentReachedAFinalStatus() {
        for (var status : List.of(PaymentStatus.CONFIRMED, PaymentStatus.FAILED, PaymentStatus.EXPIRED,
                PaymentStatus.CANCELLED)) {
            assertThat(testPayment(status, "100.00", "0.00").isOpen()).as(status.name()).isFalse();
        }
    }

    @Test
    void isConfirmed_isTrueOnlyForAConfirmedPayment() {
        var amount = "100.00";
        var refundedAmount = "0.00";
        assertThat(testPayment(PaymentStatus.CONFIRMED, amount, refundedAmount).isConfirmed()).isTrue();
        assertThat(testPayment(PaymentStatus.PENDING, amount, refundedAmount).isConfirmed()).isFalse();
    }

    // --- cancel ---

    @Test
    void cancel_movesAnOpenPaymentToCancelledKeepingItsExternalId() {
        var payment = testPayment(PaymentStatus.PENDING, "100.00", "0.00");
        var externalId = payment.externalId();

        payment.cancel();

        assertThat(payment.status()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(payment.externalId()).isEqualTo(externalId);
        assertThat(payment.updatedAt()).isNotNull();
    }

    @Test
    void cancel_movesAPaymentThatNeverGotAPaymentIntentToCancelled() {
        var payment = testInitiatedPayment();

        payment.cancel();

        assertThat(payment.status()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    void cancel_throwsForAPaymentInAFinalStatusLeavingItAsItWas() {
        var payment = testPayment(PaymentStatus.CONFIRMED, "100.00", "0.00");

        assertThatThrownBy(payment::cancel).isInstanceOf(PaymentTransitionNotAllowedException.class);
        assertThat(payment.status()).isEqualTo(PaymentStatus.CONFIRMED);
    }

    // --- expire ---

    @Test
    void expire_movesAnOpenPaymentToExpired() {
        var payment = testPayment(PaymentStatus.ATTEMPT_DECLINED, "100.00", "0.00");

        payment.expire();

        assertThat(payment.status()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    void expire_throwsForAPaymentInAFinalStatusLeavingItAsItWas() {
        var payment = testPayment(PaymentStatus.CANCELLED, "100.00", "0.00");

        assertThatThrownBy(payment::expire).isInstanceOf(PaymentTransitionNotAllowedException.class);
        assertThat(payment.status()).isEqualTo(PaymentStatus.CANCELLED);
    }
}

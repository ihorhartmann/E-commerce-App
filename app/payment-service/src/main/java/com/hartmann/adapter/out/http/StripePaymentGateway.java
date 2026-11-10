package com.hartmann.adapter.out.http;

import com.hartmann.adapter.common.http.stripe.StripeRefundStatusMapper;
import com.hartmann.adapter.out.http.exception.PaymentGatewayAuthenticationException;
import com.hartmann.adapter.out.http.exception.UnexpectedPaymentIntentStatusException;
import com.hartmann.application.port.out.http.PaymentGateway;
import com.hartmann.application.port.out.http.dto.PaymentCancellationReason;
import com.hartmann.application.port.out.http.dto.PaymentIntentState;
import com.hartmann.application.port.out.http.dto.SubmitPaymentResult;
import com.hartmann.application.port.out.http.dto.SubmitRefundResult;
import com.hartmann.application.port.out.http.exception.PaymentGatewayRejectedException;
import com.hartmann.application.port.out.http.exception.PaymentGatewayUnavailableException;
import com.hartmann.domain.model.Refund;
import com.hartmann.domain.value.Money;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class StripePaymentGateway implements PaymentGateway {
    private static final Logger LOG = LoggerFactory.getLogger(StripePaymentGateway.class);
    // Creating and cancelling a PaymentIntent are called synchronously by the order service, which only
    // retries a request that never reached this service, so transient Stripe failures are retried here.
    // A refund is not retried here: its callers already retry it - the Kafka error handler, or an HTTP
    // client resending its Idempotency-Key - and retrying here too would multiply the attempts.
    private static final int PAYMENT_INTENT_MAX_NETWORK_RETRIES = 2;

    // Stripe idempotency keys: the operation and the order, so a repeated call - a retry after a failure - gets
    // Stripe's original answer. Cancelling has a key per reason: Stripe rejects a key reused with other parameters,
    // and the customer's cancellation and the expiration sweep send different cancellation reasons.
    private static final String PLACE_ORDER_KEY_PREFIX = "placeOrder-";
    private static final String CANCEL_ORDER_KEY_PREFIX = "cancelOrder-";
    private static final String EXPIRE_PAYMENT_KEY_PREFIX = "expirePayment-";

    // Stripe's error code for a PaymentIntent that can't be cancelled in its status: already cancelled, or paid
    private static final String PAYMENT_INTENT_UNEXPECTED_STATE = "payment_intent_unexpected_state";

    // Stripe calls go through the SDK's own HTTP client, which Spring's observations don't see: without a span of its
    // own, the time spent in Stripe would show up as an unexplained gap in the payment service's span
    private static final String STRIPE_REQUEST_OBSERVATION = "stripe.request";
    private static final String OPERATION_KEY = "operation";
    private static final String OPERATION_CREATE_PAYMENT_INTENT = "create_payment_intent";
    private static final String OPERATION_CREATE_REFUND = "create_refund";
    private static final String OPERATION_CANCEL_PAYMENT_INTENT = "cancel_payment_intent";
    private static final String OPERATION_RETRIEVE_PAYMENT_INTENT = "retrieve_payment_intent";

    private final StripeClient stripeClient;
    private final ObservationRegistry observationRegistry;

    public StripePaymentGateway(StripeClient stripeClient, ObservationRegistry observationRegistry) {
        this.stripeClient = stripeClient;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public SubmitPaymentResult submitPayment(long orderId, Money amount) {
        var params = PaymentIntentCreateParams.builder()
                .setAmount(toMinorUnits(amount))
                .setCurrency(amount.currency().name().toLowerCase())
                .addPaymentMethodType("card")
                .putMetadata("orderId", String.valueOf(orderId))
                .build();

        var requestOptions = RequestOptions.builder()
                .setIdempotencyKey(PLACE_ORDER_KEY_PREFIX + orderId)
                .setMaxNetworkRetries(PAYMENT_INTENT_MAX_NETWORK_RETRIES)
                .build();

        LOG.atDebug().setMessage("sending create PaymentIntent request").addKeyValue("orderId", orderId).log();
        try {
            PaymentIntent intent = observed("stripe create payment intent", OPERATION_CREATE_PAYMENT_INTENT,
                    () -> stripeClient.v1().paymentIntents().create(params, requestOptions));
            LOG.atInfo().setMessage("successfully created PaymentIntent")
                    .addKeyValue("orderId", orderId)
                    .addKeyValue("paymentIntentId", intent.getId())
                    .log();

            return new SubmitPaymentResult(intent.getId(), intent.getClientSecret());
        } catch (RateLimitException | ApiConnectionException | ApiException e) {
            throw PaymentGatewayUnavailableException.ofPayment(orderId, e);
        } catch (AuthenticationException e) {
            // also Stripe's PermissionException, a subclass of it
            throw PaymentGatewayAuthenticationException.ofPayment(orderId, e);
        } catch (StripeException e) {
            throw PaymentGatewayRejectedException.ofPayment(orderId, e);
        }
    }

    @Override
    public PaymentIntentState cancelPayment(long orderId, String externalId, PaymentCancellationReason reason) {
        var params = PaymentIntentCancelParams.builder()
                .setCancellationReason(toStripeCancellationReason(reason))
                .build();

        var requestOptions = RequestOptions.builder()
                .setIdempotencyKey(cancellationKeyPrefix(reason) + orderId)
                .setMaxNetworkRetries(PAYMENT_INTENT_MAX_NETWORK_RETRIES)
                .build();

        LOG.atDebug().setMessage("sending cancel PaymentIntent request")
                .addKeyValue("orderId", orderId)
                .addKeyValue("paymentIntentId", externalId)
                .log();
        PaymentIntent intent;
        try {
            intent = observed("stripe cancel payment intent", OPERATION_CANCEL_PAYMENT_INTENT,
                    () -> stripeClient.v1().paymentIntents().cancel(externalId, params, requestOptions));
        } catch (RateLimitException | ApiConnectionException | ApiException e) {
            // caught before InvalidRequestException, which RateLimitException extends
            throw PaymentGatewayUnavailableException.ofPayment(orderId, e);
        } catch (InvalidRequestException e) {
            if (!PAYMENT_INTENT_UNEXPECTED_STATE.equals(e.getCode())) {
                throw PaymentGatewayRejectedException.ofPayment(orderId, e);
            }
            // Stripe refuses to cancel it: it is already cancelled - by an earlier call whose answer was lost, after
            // the idempotency key expired - or the customer paid it in the meantime. What it is now decides.
            intent = retrievePaymentIntent(orderId, externalId, e);
        } catch (AuthenticationException e) {
            // also Stripe's PermissionException, a subclass of it
            throw PaymentGatewayAuthenticationException.ofPayment(orderId, e);
        } catch (StripeException e) {
            throw PaymentGatewayRejectedException.ofPayment(orderId, e);
        }

        var state = fromStripeStatus(orderId, intent.getStatus());
        LOG.atInfo().setMessage("finished PaymentIntent cancellation")
                .addKeyValue("orderId", orderId)
                .addKeyValue("paymentIntentId", externalId)
                .addKeyValue("state", state)
                .log();
        return state;
    }

    // refusal is the cancellation Stripe refused, added to a failure of this call so it isn't lost
    private PaymentIntent retrievePaymentIntent(long orderId, String externalId, StripeException refusal) {
        try {
            return observed("stripe retrieve payment intent", OPERATION_RETRIEVE_PAYMENT_INTENT,
                    () -> stripeClient.v1().paymentIntents().retrieve(externalId));
        } catch (RateLimitException | ApiConnectionException | ApiException e) {
            e.addSuppressed(refusal);
            throw PaymentGatewayUnavailableException.ofPayment(orderId, e);
        } catch (AuthenticationException e) {
            e.addSuppressed(refusal);
            throw PaymentGatewayAuthenticationException.ofPayment(orderId, e);
        } catch (StripeException e) {
            e.addSuppressed(refusal);
            throw PaymentGatewayRejectedException.ofPayment(orderId, e);
        }
    }

    private static PaymentIntentState fromStripeStatus(long orderId, String stripeStatus) {
        return switch (stripeStatus) {
            case "canceled" -> PaymentIntentState.CANCELLED;
            case "succeeded" -> PaymentIntentState.SUCCEEDED;
            case "processing" -> PaymentIntentState.PROCESSING;
            // anything else is a PaymentIntent Stripe should have cancelled, or one this service never makes
            default -> throw new UnexpectedPaymentIntentStatusException(orderId, stripeStatus);
        };
    }

    private static PaymentIntentCancelParams.CancellationReason toStripeCancellationReason(PaymentCancellationReason reason) {
        return switch (reason) {
            case REQUESTED_BY_CUSTOMER -> PaymentIntentCancelParams.CancellationReason.REQUESTED_BY_CUSTOMER;
            case EXPIRED -> PaymentIntentCancelParams.CancellationReason.ABANDONED;
        };
    }

    private static String cancellationKeyPrefix(PaymentCancellationReason reason) {
        return switch (reason) {
            case REQUESTED_BY_CUSTOMER -> CANCEL_ORDER_KEY_PREFIX;
            case EXPIRED -> EXPIRE_PAYMENT_KEY_PREFIX;
        };
    }

    @Override
    public SubmitRefundResult submitRefund(String paymentExternalId, Refund refund) {
        var params = RefundCreateParams.builder()
                .setPaymentIntent(paymentExternalId)
                .setAmount(toMinorUnits(refund.amount()))
                .putMetadata("refundId", String.valueOf(refund.id()))
                .build();

        var requestOptions = RequestOptions.builder()
                .setIdempotencyKey("refund-" + refund.idempotencyKey())
                .build();

        LOG.atDebug().setMessage("sending create Refund request")
                .addKeyValue("paymentIntentId", paymentExternalId)
                .addKeyValue("refundId", refund.id())
                .log();
        try {
            var stripeRefund = observed("stripe create refund", OPERATION_CREATE_REFUND,
                    () -> stripeClient.v1().refunds().create(params, requestOptions));
            LOG.atInfo().setMessage("successfully created Refund")
                    .addKeyValue("paymentIntentId", paymentExternalId)
                    .addKeyValue("refundId", refund.id())
                    .addKeyValue("stripeRefundId", stripeRefund.getId())
                    .log();

            return new SubmitRefundResult(stripeRefund.getId(), StripeRefundStatusMapper.fromStripeStatus(stripeRefund.getStatus()));
        } catch (RateLimitException | ApiConnectionException | ApiException e) {
            throw PaymentGatewayUnavailableException.ofRefund(paymentExternalId, refund.id(), e);
        } catch (AuthenticationException e) {
            // also Stripe's PermissionException, a subclass of it
            throw PaymentGatewayAuthenticationException.ofRefund(paymentExternalId, refund.id(), e);
        } catch (StripeException e) {
            throw PaymentGatewayRejectedException.ofRefund(paymentExternalId, refund.id(), e);
        }
    }

    // A span named contextualName around one Stripe call, marked failed when the call throws - and, from the same
    // observation, the stripe.request timer: the latency and the failures of each operation.
    private <T> T observed(String contextualName, String operation, StripeCall<T> call) throws StripeException {
        var observation = Observation.createNotStarted(STRIPE_REQUEST_OBSERVATION, observationRegistry)
                .contextualName(contextualName)
                .lowCardinalityKeyValue(OPERATION_KEY, operation)
                .start();
        try (var ignored = observation.openScope()) {
            return call.call();
        } catch (StripeException | RuntimeException e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    @FunctionalInterface
    private interface StripeCall<T> {
        T call() throws StripeException;
    }

    private long toMinorUnits(Money amount) {
        return amount.amount().movePointRight(2).longValueExact();
    }
}

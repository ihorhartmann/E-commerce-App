package com.hartmann.adapter.out.http;

import com.hartmann.adapter.common.http.stripe.StripeRefundStatusMapper;
import com.hartmann.adapter.out.http.exception.PaymentGatewayAuthenticationException;
import com.hartmann.application.port.out.http.PaymentGateway;
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
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class StripePaymentGateway implements PaymentGateway {
    private static final Logger LOG = LoggerFactory.getLogger(StripePaymentGateway.class);
    // Creating a PaymentIntent is called synchronously by the order service, which only retries a
    // request that never reached this service, so transient Stripe failures are retried here. A
    // refund is not retried here: its callers already retry it - the Kafka error handler, or an HTTP
    // client resending its Idempotency-Key - and retrying here too would multiply the attempts.
    private static final int PAYMENT_INTENT_MAX_NETWORK_RETRIES = 2;

    private final StripeClient stripeClient;

    public StripePaymentGateway(StripeClient stripeClient) {
        this.stripeClient = stripeClient;
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
                .setIdempotencyKey("order-" + orderId)
                .setMaxNetworkRetries(PAYMENT_INTENT_MAX_NETWORK_RETRIES)
                .build();

        LOG.debug("sending create PaymentIntent request orderId={}", orderId);
        try {
            PaymentIntent intent = stripeClient.v1().paymentIntents().create(params, requestOptions);
            LOG.info("successfully created PaymentIntent orderId={} paymentIntentId={}", orderId, intent.getId());

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
    public SubmitRefundResult submitRefund(String paymentExternalId, Refund refund) {
        var params = RefundCreateParams.builder()
                .setPaymentIntent(paymentExternalId)
                .setAmount(toMinorUnits(refund.amount()))
                .putMetadata("refundId", String.valueOf(refund.id()))
                .build();

        var requestOptions = RequestOptions.builder()
                .setIdempotencyKey("refund-" + refund.idempotencyKey())
                .build();

        LOG.debug("sending create Refund request paymentIntentId={} refundId={}", paymentExternalId, refund.id());
        try {
            var stripeRefund = stripeClient.v1().refunds().create(params, requestOptions);
            LOG.info("successfully created Refund paymentIntentId={} stripeRefundId={}", paymentExternalId, stripeRefund.getId());

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

    private long toMinorUnits(Money amount) {
        return amount.amount().movePointRight(2).longValueExact();
    }
}

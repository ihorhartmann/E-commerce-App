package com.hartmann.adapter.out.http;

import com.hartmann.adapter.common.http.stripe.StripeRefundStatusMapper;
import com.hartmann.application.port.out.http.PaymentGateway;
import com.hartmann.application.port.out.http.dto.SubmitPaymentResult;
import com.hartmann.application.port.out.http.dto.SubmitRefundResult;
import com.hartmann.adapter.out.http.exception.PaymentGatewayException;
import com.hartmann.adapter.out.http.exception.PaymentGatewayUnavailableException;
import com.hartmann.domain.model.Refund;
import com.hartmann.domain.value.Money;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
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

    private final StripeClient stripeClient;

    public StripePaymentGateway(StripeClient stripeClient) {
        this.stripeClient = stripeClient;
    }

    @Override
    public SubmitPaymentResult submitPayment(long orderId, Money amount) {
        var params = PaymentIntentCreateParams.builder()
                .setAmount(toMinorUnits(amount))
                .setCurrency(amount.currency().name().toLowerCase())
                .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                .setEnabled(true)
                                .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                                .build()
                )
                .putMetadata("orderId", String.valueOf(orderId))
                .build();

        var requestOptions = RequestOptions.builder()
                .setIdempotencyKey("order-" + orderId)
                .build();

        LOG.debug("sending create PaymentIntent request orderId={}", orderId);
        try {
            PaymentIntent intent = stripeClient.v1().paymentIntents().create(params, requestOptions);
            LOG.info("successfully created PaymentIntent orderId={} paymentIntentId={}", orderId, intent.getId());

            return new SubmitPaymentResult(intent.getId(), intent.getClientSecret());
        } catch (RateLimitException | ApiConnectionException | ApiException e) {
            throw new PaymentGatewayUnavailableException(
                    "Stripe is temporarily unavailable for order " + orderId, e);
        } catch (StripeException e) {
            throw new PaymentGatewayException("Failed to create Stripe PaymentIntent for order " + orderId, e);
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
            throw new PaymentGatewayUnavailableException(
                    "Stripe is temporarily unavailable for PaymentIntent " + paymentExternalId, e);
        } catch (StripeException e) {
            throw new PaymentGatewayException(
                    "Failed to create Stripe Refund for PaymentIntent " + paymentExternalId, e);
        }
    }

    private long toMinorUnits(Money amount) {
        return amount.amount().movePointRight(2).longValueExact();
    }
}

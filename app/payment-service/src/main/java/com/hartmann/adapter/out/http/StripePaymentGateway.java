package com.hartmann.adapter.out.http;

import com.hartmann.adapter.out.http.mapper.StripeRefundStatusMapper;
import com.hartmann.application.port.out.http.PaymentGateway;
import com.hartmann.application.port.out.http.dto.SubmitPaymentResult;
import com.hartmann.application.port.out.http.dto.SubmitRefundResult;
import com.hartmann.application.port.out.http.exception.PaymentGatewayException;
import com.hartmann.domain.value.Money;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import org.springframework.stereotype.Component;

@Component
public class StripePaymentGateway implements PaymentGateway {
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

        try {
            PaymentIntent intent = stripeClient.v1().paymentIntents().create(params, requestOptions);
            return new SubmitPaymentResult(intent.getId());
        } catch (StripeException e) {
            throw new PaymentGatewayException("Failed to create Stripe PaymentIntent for order " + orderId, e);
        }
    }

    @Override
    public SubmitRefundResult submitRefund(String paymentExternalId, Money amount) {
        var params = RefundCreateParams.builder()
                .setPaymentIntent(paymentExternalId)
                .setAmount(toMinorUnits(amount))
                .build();

        try {
            var refund = stripeClient.v1().refunds().create(params);
            return new SubmitRefundResult(refund.getId(), StripeRefundStatusMapper.toDomain(refund.getStatus()));
        } catch (StripeException e) {
            throw new PaymentGatewayException(
                    "Failed to create Stripe Refund for PaymentIntent " + paymentExternalId, e);
        }
    }

    private long toMinorUnits(Money amount) {
        return amount.amount().movePointRight(2).longValueExact();
    }
}

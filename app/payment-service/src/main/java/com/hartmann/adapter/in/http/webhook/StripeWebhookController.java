package com.hartmann.adapter.in.http.webhook;

import com.hartmann.adapter.common.http.stripe.StripeRefundStatusMapper;
import com.hartmann.adapter.in.http.webhook.exception.InvalidWebhookSignatureException;
import com.hartmann.application.port.in.PaymentService;
import com.hartmann.application.port.in.RefundService;
import com.hartmann.domain.exception.PaymentServiceException;
import com.hartmann.domain.model.Payment;
import com.hartmann.domain.model.PaymentStatus;
import com.hartmann.domain.model.Refund;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.hartmann.domain.exception.Attributes.attributesOf;

@RestController
@RequestMapping("/api/v1/webhooks/stripe")
public class StripeWebhookController {
    private static final Logger LOG = LoggerFactory.getLogger(StripeWebhookController.class);

    private final String webhookSecret;
    private final PaymentService paymentService;
    private final RefundService refundService;

    public StripeWebhookController(
            @Value("${stripe.webhook-secret}") String webhookSecret,
            PaymentService paymentService,
            RefundService refundService
    ) {
        this.webhookSecret = webhookSecret;
        this.paymentService = paymentService;
        this.refundService = refundService;
    }

    @PostMapping
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String signatureHeader
    ) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            throw new InvalidWebhookSignatureException(e);
        }

        switch (event.getType()) {
            case "payment_intent.succeeded" -> handlePaymentIntentEvent(event, PaymentStatus.CONFIRMED);
            case "payment_intent.payment_failed" -> handlePaymentIntentEvent(event, PaymentStatus.ATTEMPT_DECLINED);
            case "refund.updated" -> handleRefundEvent(event);
            default -> LOG.info("Ignoring unhandled Stripe event type: {}", event.getType());
        }

        return ResponseEntity.ok().build();
    }

    private void handlePaymentIntentEvent(Event event, PaymentStatus status) {
        event.getDataObjectDeserializer().getObject().ifPresentOrElse(
                stripeObject -> {
                    if (stripeObject instanceof PaymentIntent paymentIntent) {
                        updatePaymentStatus(event, paymentIntent, status);
                    }
                },
                () -> LOG.warn(
                        "Stripe event {} ({}) could not be deserialized to PaymentIntent — "
                                + "likely an API version mismatch between account and stripe-java",
                        event.getId(), event.getType()
                )
        );
    }

    private void updatePaymentStatus(Event event, PaymentIntent paymentIntent, PaymentStatus status) {
        try {
            Payment payment = paymentService.updateStatusByExternalId(paymentIntent.getId(), status);
            LOG.info(
                    "Payment updated from webhook: eventId={}, paymentId={}, orderId={}, externalId={}, status={}",
                    event.getId(), payment.id(), payment.orderId(), paymentIntent.getId(), status
            );
        } catch (PaymentServiceException e) {
            acknowledgeIfPermanent(event, e);
        }
    }

    private void handleRefundEvent(Event event) {
        event.getDataObjectDeserializer().getObject().ifPresentOrElse(
                stripeObject -> {
                    if (stripeObject instanceof com.stripe.model.Refund stripeRefund) {
                        updateRefundStatus(event, stripeRefund);
                    }
                },
                () -> LOG.warn(
                        "Stripe event {} ({}) could not be deserialized to Refund — "
                                + "likely an API version mismatch between account and stripe-java",
                        event.getId(), event.getType()
                )
        );
    }

    private void updateRefundStatus(Event event, com.stripe.model.Refund stripeRefund) {
        try {
            var status = StripeRefundStatusMapper.fromStripeStatus(stripeRefund.getStatus());
            Refund refund = refundService.updateStatusByExternalId(stripeRefund.getId(), status);
            LOG.info(
                    "Refund updated from webhook: eventId={}, refundId={}, paymentId={}, externalId={}, status={}",
                    event.getId(), refund.id(), refund.paymentId(), stripeRefund.getId(), status
            );
        } catch (PaymentServiceException e) {
            acknowledgeIfPermanent(event, e);
        }
    }

    /**
     * A permanent failure - an unknown PaymentIntent or Refund, a status change the payment or refund rejects, e.g.
     * a confirmation arriving after the payment already expired - is acknowledged anyway: Stripe would only
     * redeliver the same event, which would fail the same way again. Any other failure is rethrown, so the
     * webhook answers with a 5xx and Stripe redelivers the event later.
     */
    private static void acknowledgeIfPermanent(Event event, PaymentServiceException exception) {
        if (!exception.isPermanent()) {
            throw exception;
        }

        var logBuilder = LOG.atWarn().setMessage("Stripe webhook event rejected, acknowledged anyway")
                .addKeyValue("eventId", event.getId())
                .addKeyValue("eventType", event.getType())
                .addKeyValue("code", exception.code().code())
                .addKeyValue("exceptionMessage", exception.getMessage());
        attributesOf(exception).forEach(logBuilder::addKeyValue);
        logBuilder.log();
    }
}

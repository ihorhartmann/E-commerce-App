package com.hartmann.adapter.in.http.webhook;

import com.hartmann.adapter.out.http.mapper.StripeRefundStatusMapper;
import com.hartmann.application.port.in.PaymentService;
import com.hartmann.application.port.in.RefundService;
import com.hartmann.application.port.out.repository.exception.ObjectNotFoundException;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks/stripe")
public class StripeWebhookController {
    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

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
            log.info("Webhook received event: {}", payload);
            event = Webhook.constructEvent(payload, signatureHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Rejected Stripe webhook: invalid signature", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        switch (event.getType()) {
            case "payment_intent.succeeded" -> handlePaymentIntentEvent(event, PaymentStatus.COMPLETED);
            case "payment_intent.payment_failed" -> handlePaymentIntentEvent(event, PaymentStatus.FAILED);
            case "refund.updated" -> handleRefundEvent(event);
            default -> log.info("Ignoring unhandled Stripe event type: {}", event.getType());
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
                () -> log.warn(
                        "Stripe event {} ({}) could not be deserialized to PaymentIntent — "
                                + "likely an API version mismatch between account and stripe-java",
                        event.getId(), event.getType()
                )
        );
    }

    private void updatePaymentStatus(Event event, PaymentIntent paymentIntent, PaymentStatus status) {
        try {
            Payment payment = paymentService.updateStatusByExternalId(paymentIntent.getId(), status);
            log.info(
                    "Payment updated from webhook: eventId={}, paymentId={}, orderId={}, externalId={}, status={}",
                    event.getId(), payment.id(), payment.orderId(), paymentIntent.getId(), status
            );
        } catch (ObjectNotFoundException e) {
            log.warn("Received webhook for unknown PaymentIntent {}: {}", paymentIntent.getId(), e.getMessage());
        }
    }

    private void handleRefundEvent(Event event) {
        event.getDataObjectDeserializer().getObject().ifPresentOrElse(
                stripeObject -> {
                    if (stripeObject instanceof com.stripe.model.Refund stripeRefund) {
                        updateRefundStatus(event, stripeRefund);
                    }
                },
                () -> log.warn(
                        "Stripe event {} ({}) could not be deserialized to Refund — "
                                + "likely an API version mismatch between account and stripe-java",
                        event.getId(), event.getType()
                )
        );
    }

    private void updateRefundStatus(Event event, com.stripe.model.Refund stripeRefund) {
        var status = StripeRefundStatusMapper.toDomain(stripeRefund.getStatus());
        try {
            Refund refund = refundService.updateStatusByExternalId(stripeRefund.getId(), status);
            log.info(
                    "Refund updated from webhook: eventId={}, refundId={}, paymentId={}, externalId={}, status={}",
                    event.getId(), refund.id(), refund.paymentId(), stripeRefund.getId(), status
            );
        } catch (ObjectNotFoundException e) {
            log.warn("Received webhook for unknown Refund {}: {}", stripeRefund.getId(), e.getMessage());
        }
    }
}

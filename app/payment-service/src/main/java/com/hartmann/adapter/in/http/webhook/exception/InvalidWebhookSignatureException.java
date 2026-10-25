package com.hartmann.adapter.in.http.webhook.exception;

import com.hartmann.domain.exception.InvalidException;

import java.util.Map;

/** The Stripe-Signature header doesn't match the payload. cause is Stripe's SignatureVerificationException. */
public class InvalidWebhookSignatureException extends InvalidException {
    public InvalidWebhookSignatureException(Throwable cause) {
        super(WebhookErrorCode.INVALID_WEBHOOK_SIGNATURE, "Invalid Stripe webhook signature", Map.of(), cause);
    }
}

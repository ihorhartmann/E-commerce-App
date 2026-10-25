package com.hartmann.adapter.in.http.webhook.exception;

import com.hartmann.domain.exception.ErrorCode;

/** Codes of the Stripe webhook calls this service rejects. */
public enum WebhookErrorCode implements ErrorCode {
    INVALID_WEBHOOK_SIGNATURE("Invalid Stripe webhook signature");

    private final String title;

    WebhookErrorCode(String title) {
        this.title = title;
    }

    @Override
    public String title() {
        return title;
    }
}

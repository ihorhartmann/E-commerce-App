package com.hartmann.adapter.out.http.exception;

import com.hartmann.application.exception.ApplicationException;

public class PaymentGatewayException extends ApplicationException {
    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}

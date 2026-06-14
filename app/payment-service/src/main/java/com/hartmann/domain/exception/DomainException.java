package com.hartmann.domain.exception;

import com.hartmann.exception.PaymentException;

public class DomainException extends PaymentException {
    public DomainException(String message) {
        super(message);
    }
}

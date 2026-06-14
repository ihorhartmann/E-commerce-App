package com.hartmann.application.port.out.db.exception;

import com.hartmann.exception.PaymentException;

public class ObjectNotFoundException extends PaymentException {
    public ObjectNotFoundException(String message) {
        super(message);
    }
}

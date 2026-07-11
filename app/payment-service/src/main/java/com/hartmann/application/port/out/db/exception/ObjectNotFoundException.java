package com.hartmann.application.port.out.db.exception;

import com.hartmann.application.exception.ApplicationException;

public class ObjectNotFoundException extends ApplicationException {
    public ObjectNotFoundException(String message) {
        super(message);
    }
}

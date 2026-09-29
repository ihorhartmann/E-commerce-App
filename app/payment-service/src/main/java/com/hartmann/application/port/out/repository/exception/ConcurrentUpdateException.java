package com.hartmann.application.port.out.repository.exception;

import com.hartmann.application.exception.ApplicationException;

/**
 * The object was changed by someone else since it was loaded, so the update was not applied.
 * Transient: repeating the whole operation reloads the object and usually succeeds.
 */
public class ConcurrentUpdateException extends ApplicationException {
    public ConcurrentUpdateException(String message) {
        super(message);
    }
}

package com.hartmann.domain.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class Attributes {
    private Attributes() {
    }

    /**
     * The attributes of every {@link PaymentServiceException} in exception's cause chain, merged - the Java
     * counterpart of Go's apperror.LogAttr walking the error chain. An outer exception's value wins over
     * a cause's value for the same key, since the outer layer knows better what the failure meant.
     * Causes that aren't PaymentServiceExceptions (a library exception) contribute nothing.
     * <p>
     * A static function taking any Throwable rather than a method of {@link PaymentServiceException}, against
     * Information Expert, on purpose: the callers often hold an exception that isn't one of ours at the
     * top - Spring Kafka's ListenerExecutionFailedException, a library exception wrapping ours, whatever
     * reached a catch-all handler - so a method would first need our exception found in the chain anyway.
     */
    public static Map<String, Object> attributesOf(Throwable exception) {
        var attributes = new LinkedHashMap<String, Object>();
        for (var cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PaymentServiceException serviceException) {
                // not putIfAbsent, which would let a cause overwrite an outer null value
                serviceException.attributes().forEach((key, value) -> {
                    if (!attributes.containsKey(key)) {
                        attributes.put(key, value);
                    }
                });
            }
        }
        return Collections.unmodifiableMap(attributes);
    }

    /**
     * The outermost {@link PaymentServiceException} in exception's cause chain - exception itself, if it is one -
     * which is the one a failure is reported and classified by: its code, its kind. Empty if the chain holds none.
     * Static and taking any Throwable for the same reason as {@link #attributesOf}.
     */
    public static Optional<PaymentServiceException> outermostOf(Throwable exception) {
        for (var cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PaymentServiceException found) {
                return Optional.of(found);
            }
        }
        return Optional.empty();
    }
}

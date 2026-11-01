package com.hartmann.adapter.in.kafka;

import com.hartmann.domain.exception.PaymentServiceException;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.hartmann.domain.exception.Attributes.attributesOf;
import static com.hartmann.domain.exception.Attributes.outermostOf;

public final class KafkaFailures {
    private KafkaFailures() {
    }

    /**
     * Whether a message whose handling failed with exception can't succeed on any later attempt, so it is
     * dead-lettered at once instead of retried: decided by the outermost PaymentServiceException in its cause
     * chain - the top of the chain is the listener container's own wrapper. A failure without one is retried.
     */
    public static boolean isPermanentFailure(Throwable exception) {
        return outermostOf(exception).map(PaymentServiceException::isPermanent).orElse(false);
    }

    /**
     * The key-values a log line describes a failed message's exception with: the message of the outermost
     * PaymentServiceException in its cause chain - or of exception, without one - its code, and the attributes of
     * the chain. A key the MDC has already - the message's messageType, say, an attribute of a MalformedPayloadException
     * too - is left out: Spring Boot's structured logging drops a line that has a key twice.
     */
    public static Map<String, Object> failureKeyValues(Throwable exception) {
        var outermost = outermostOf(exception);
        var keyValues = new LinkedHashMap<String, Object>();
        keyValues.put("exceptionMessage", (outermost.isPresent() ? outermost.get() : exception).getMessage());
        outermost.ifPresent(found -> keyValues.put("code", found.code().code()));
        keyValues.putAll(attributesOf(exception));
        keyValues.keySet().removeIf(key -> MDC.get(key) != null);
        return keyValues;
    }
}

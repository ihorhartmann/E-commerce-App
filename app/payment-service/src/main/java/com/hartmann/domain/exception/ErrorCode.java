package com.hartmann.domain.exception;

/**
 * A stable, documented error identifier, sent to clients as ProblemDetail.code. Each layer declares
 * its codes as an enum implementing this interface, next to the exceptions that use them (e.g. a
 * {@code PaymentErrorCode} next to the payment, a {@code RepositoryErrorCode} next to the repository ports),
 * so {@link #name()} is the enum constant's own name. Nothing checks that two enums don't share a name: keep
 * code names specific (PAYMENT_NOT_FOUND, not NOT_FOUND).
 */
public interface ErrorCode {
    /**
     * Makes every code unique across the system: errors from all services reach a client through the
     * gateway, and the prefix also tells which service a code came from.
     */
    String SERVICE_PREFIX = "PAYMENT.";

    /** The enum constant's name without the service prefix, e.g. {@code PAYMENT_NOT_REFUNDABLE}. */
    String name();

    /**
     * A short, public, human-readable summary of the code - ProblemDetail.title. It must not contain
     * anything specific to one occurrence (ids, amounts, ...): that goes to the exception's attributes.
     */
    String title();

    /**
     * The machine-readable identifier clients and logs see, with the service prefix, e.g.
     * {@code PAYMENT.PAYMENT_NOT_REFUNDABLE}.
     */
    default String code() {
        return SERVICE_PREFIX + name();
    }
}

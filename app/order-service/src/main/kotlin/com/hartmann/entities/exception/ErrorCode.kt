package com.hartmann.entities.exception

/**
 * Makes every code unique across the system: errors from all services reach a client through the
 * gateway, and the prefix also tells which service a code came from.
 */
const val SERVICE_PREFIX = "ORDER."

/**
 * A stable, documented error identifier, sent to clients as ProblemDetail.code. Each layer declares
 * its codes as an enum implementing this interface, next to the exceptions that use them (e.g. an
 * `OrderErrorCode` next to the order, a `RepositoryErrorCode` next to the repository port), so
 * `name` is the enum constant's own name. Nothing checks that two enums don't share a name: keep code names
 * specific (ORDER_NOT_FOUND, not NOT_FOUND).
 */
interface ErrorCode {
    /** The enum constant's name without the service prefix, e.g. `ORDER_NOT_CANCELLABLE`. */
    val name: String

    /**
     * A short, public, human-readable summary of the code - ProblemDetail.title. It must not contain
     * anything specific to one occurrence (ids, amounts, ...): that goes to the exception's attributes.
     */
    val title: String

    /**
     * The machine-readable identifier clients and logs see, with the service prefix, e.g.
     * `ORDER.ORDER_NOT_CANCELLABLE`.
     */
    val code: String
        get() = SERVICE_PREFIX + name
}

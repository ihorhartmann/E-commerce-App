package com.hartmann.usecase.port.common.uuid

import java.util.UUID

object UuidUtils {
    /**
     * Returns a time-ordered UUIDv7 (RFC 9562): consecutive ids land next to each other in a
     * B-tree index instead of at random positions, as UUID.randomUUID() (v4) ones do.
     */
    fun generateV7(): UUID = UUID.ofEpochMillis(System.currentTimeMillis())
}

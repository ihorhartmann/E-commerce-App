package com.hartmann.application.port.common.uuid;

import java.util.UUID;

public final class UuidUtils {
    private UuidUtils() {
    }

    // generateV7 returns a time-ordered UUIDv7 (RFC 9562): consecutive ids land next to each other
    // in a B-tree index instead of at random positions, as UUID.randomUUID() (v4) ones do.
    public static UUID generateV7() {
        return UUID.ofEpochMillis(System.currentTimeMillis());
    }
}

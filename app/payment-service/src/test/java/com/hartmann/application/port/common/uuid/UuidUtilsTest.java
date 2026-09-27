package com.hartmann.application.port.common.uuid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class UuidUtilsTest {

    @Test
    void generateV7_returnsAVersion7UuidStampedWithTheCurrentTime() {
        var before = System.currentTimeMillis();

        var uuid = UuidUtils.generateV7();

        var after = System.currentTimeMillis();
        var actualEpochMillis = uuid.getMostSignificantBits() >>> 16;
        assertEquals(7, uuid.version());
        assertTrue(actualEpochMillis >= before && actualEpochMillis <= after);
    }
}

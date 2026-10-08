package com.securebank.common;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class Timestamps {

    private Timestamps() {
    }

    /**
     * The current time at the database's precision (microseconds). Without truncation, a value
     * returned before persisting can differ from the same value read back later on platforms where
     * {@link Instant#now()} has nanosecond precision (e.g. Linux).
     */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}

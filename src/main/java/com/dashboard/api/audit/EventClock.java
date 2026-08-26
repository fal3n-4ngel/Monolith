package com.dashboard.api.audit;

import java.time.Duration;
import java.time.Instant;

/**
 * Shared timestamp handling for anything a client claims happened at a particular moment.
 *
 * <p>A caller-supplied timestamp orders the log, so a wild value — clock skew, or a forged
 * far-future one — would corrupt every {@code ORDER BY} over it. Outside a sane window we fall
 * back to server time rather than trusting the claim.
 */
public final class EventClock {

    /** How far a client-supplied timestamp may drift before we distrust it and stamp server time. */
    private static final Duration MAX_CLOCK_DRIFT = Duration.ofHours(24);

    private EventClock() {
    }

    public static long resolve(Long claimed, Instant receivedAt) {
        if (claimed == null || claimed <= 0) {
            return receivedAt.toEpochMilli();
        }
        long drift = Math.abs(claimed - receivedAt.toEpochMilli());
        return drift > MAX_CLOCK_DRIFT.toMillis() ? receivedAt.toEpochMilli() : claimed;
    }
}

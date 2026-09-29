package com.uxplima.uxmskyblock.core.application.backup;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * The islands a backup is reading right now, whose world nobody may change until it has.
 *
 * <p>A capture reads an island's rows and then its chunks, region by region, while players kept
 * building: the backup held a world from one moment and rows from another, and called that
 * consistent. While an island is quiesced its protection refuses every change to it, so what the
 * capture reads is one island at one moment.
 *
 * <p>The window is bounded, and by nothing but the capture's own deadline, which the operator sets.
 * It closes when the capture ends, and at its bound if a capture never does: an island is never held
 * closed by a backup that hung. No latency figure is promised or enforced here; how long a capture
 * takes is a measurement, not a rule.
 */
public final class CaptureQuiesce {

    private final Map<IslandId, Instant> quiescedUntil = new ConcurrentHashMap<>();
    private final Clock clock;

    public CaptureQuiesce(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public CaptureQuiesce() {
        this(Clock.systemUTC());
    }

    /** A window over {@code islandId} that ends when it is closed or {@code bound} from now, whichever is first. */
    public Window enter(IslandId islandId, Duration bound) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(bound, "bound must not be null");
        if (bound.isNegative() || bound.isZero()) {
            throw new IllegalArgumentException("A quiesce window must be bounded by a positive duration: " + bound);
        }
        Instant until = clock.instant().plus(bound);
        quiescedUntil.merge(islandId, until, (held, offered) -> held.isAfter(offered) ? held : offered);
        return new Window(islandId, until);
    }

    /** Whether a capture is reading {@code islandId} now. */
    public boolean isQuiesced(IslandId islandId) {
        Instant until = quiescedUntil.get(islandId);
        if (until == null) {
            return false;
        }
        if (!until.isAfter(clock.instant())) {
            quiescedUntil.remove(islandId, until);
            return false;
        }
        return true;
    }

    /** Lets go of an island that is gone, whatever capture was reading it. */
    public void forgetIsland(IslandId islandId) {
        quiescedUntil.remove(islandId);
    }

    /** One capture's hold on an island. Closing it twice is closing it once. */
    public final class Window implements AutoCloseable {

        private final IslandId islandId;
        private final Instant until;

        private Window(IslandId islandId, Instant until) {
            this.islandId = islandId;
            this.until = until;
        }

        @Override
        public void close() {
            quiescedUntil.remove(islandId, until);
        }
    }
}

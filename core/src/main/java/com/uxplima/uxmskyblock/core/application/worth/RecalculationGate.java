package com.uxplima.uxmskyblock.core.application.worth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Who may rescan an island, and when.
 *
 * <p>A recalculation reads every block of every chunk the island covers, at every height, on the
 * threads that own those chunks. An island of radius one hundred is about thirteen million block
 * reads. Nothing held a player back, so anybody could send the command as fast as they could type
 * it and keep the region busy for everybody standing in it. Now one island has one rescan at a
 * time, and a new one waits out the operator's cooldown from the start of the last.
 */
public final class RecalculationGate {

    /** How long an island waits between rescans when the operator names no number. */
    public static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(60);

    /** What a request to rescan an island was told. */
    public sealed interface Admission {
        /** The rescan may run. The caller must {@link #leave} when it is done. */
        record Admitted() implements Admission {}

        /** A rescan of this island is running now. */
        record AlreadyRunning() implements Admission {}

        /** The last rescan started too recently. */
        record CoolingDown(Duration remaining) implements Admission {}
    }

    private final Duration cooldown;
    private final Clock clock;
    private final Set<IslandId> running = ConcurrentHashMap.newKeySet();
    private final Map<IslandId, Instant> lastStarted = new ConcurrentHashMap<>();

    public RecalculationGate(Duration cooldown, Clock clock) {
        Objects.requireNonNull(cooldown, "cooldown must not be null");
        this.cooldown = cooldown.isNegative() ? Duration.ZERO : cooldown;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Lets a rescan of this island start, or says why it may not. */
    public Admission tryEnter(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Instant now = clock.instant();
        Instant previous = lastStarted.get(islandId);
        if (previous != null) {
            Duration since = Duration.between(previous, now);
            if (since.compareTo(cooldown) < 0 && !running.contains(islandId)) {
                return new Admission.CoolingDown(cooldown.minus(since));
            }
        }
        if (!running.add(islandId)) {
            return new Admission.AlreadyRunning();
        }
        lastStarted.put(islandId, now);
        return new Admission.Admitted();
    }

    /** Marks this island's rescan finished, whether it succeeded or not. */
    public void leave(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        running.remove(islandId);
    }

    /** Drops what this gate holds for an island that is gone. */
    public void forget(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        running.remove(islandId);
        lastStarted.remove(islandId);
    }

    public Duration cooldown() {
        return cooldown;
    }
}

package com.uxplima.uxmskyblock.core.domain.mission;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * How often a mission comes back: never, every day, or every week.
 *
 * <p>The missions window offered daily missions, weekly missions and challenges, and every mission was a challenge:
 * finished once, it stayed finished. A mission that comes back starts again at the turn of its day or its week, in
 * the zone the operator names, and pays again each time it is finished.
 */
public enum MissionRepeat {

    /** Finished once, finished for good. */
    ONCE,

    /** Starts again at midnight. */
    DAILY,

    /** Starts again at midnight between Sunday and Monday. */
    WEEKLY;

    /** The kind a file names, any case, or nothing for a word that is none of them. */
    public static Optional<MissionRepeat> named(String written) {
        Objects.requireNonNull(written, "written must not be null");
        String word = written.strip().toUpperCase(Locale.ROOT);
        for (MissionRepeat repeat : values()) {
            if (repeat.name().equals(word)) {
                return Optional.of(repeat);
            }
        }
        return Optional.empty();
    }

    /**
     * When the period {@code now} falls in began, in {@code zone}: progress made before it belongs to a period that
     * is over. A mission that never comes back has one period, which began at the epoch.
     */
    public Instant periodStart(Instant now, ZoneId zone) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(zone, "zone must not be null");
        return switch (this) {
            case ONCE -> Instant.EPOCH;
            case DAILY -> now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
            case WEEKLY ->
                now.atZone(zone)
                        .toLocalDate()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .atStartOfDay(zone)
                        .toInstant();
        };
    }
}

package com.uxplima.uxmskyblock.core.domain.event;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly typed identifier for a distributed domain event.
 */
public record EventId(UUID value) implements Comparable<EventId> {

    public EventId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static EventId of(UUID value) {
        return new EventId(value);
    }

    public static EventId fromString(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return new EventId(UUID.fromString(value));
    }

    public static EventId random() {
        return new EventId(UUID.randomUUID());
    }

    @Override
    public int compareTo(EventId other) {
        int msb = Long.compareUnsigned(this.value.getMostSignificantBits(), other.value.getMostSignificantBits());
        if (msb != 0) {
            return msb;
        }
        return Long.compareUnsigned(this.value.getLeastSignificantBits(), other.value.getLeastSignificantBits());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

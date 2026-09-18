package com.uxplima.uxmskyblock.core.domain.alliance;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly typed identity value object for an Island Alliance Invite.
 *
 * @param value the underlying invite UUID
 */
public record AllianceInviteId(UUID value) implements Comparable<AllianceInviteId> {

    public AllianceInviteId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static AllianceInviteId random() {
        return new AllianceInviteId(UUID.randomUUID());
    }

    public static AllianceInviteId of(UUID value) {
        return new AllianceInviteId(value);
    }

    public static AllianceInviteId fromString(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return new AllianceInviteId(UUID.fromString(value));
    }

    @Override
    public int compareTo(AllianceInviteId other) {
        int msbComparison =
                Long.compareUnsigned(this.value.getMostSignificantBits(), other.value.getMostSignificantBits());
        if (msbComparison != 0) {
            return msbComparison;
        }
        return Long.compareUnsigned(this.value.getLeastSignificantBits(), other.value.getLeastSignificantBits());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

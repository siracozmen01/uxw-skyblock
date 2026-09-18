package com.uxplima.uxmskyblock.core.domain.permission;

/**
 * Dense runtime-only integer identifier (0..N) representing a compiled {@link PermissionKey}.
 * Never persisted to database; used strictly in hot-path bitset evaluation.
 */
public record PermissionId(int index) implements Comparable<PermissionId> {

    public PermissionId {
        if (index < 0) {
            throw new IllegalArgumentException("PermissionId index must be non-negative: " + index);
        }
    }

    public static PermissionId of(int index) {
        return new PermissionId(index);
    }

    @Override
    public int compareTo(PermissionId other) {
        return Integer.compare(this.index, other.index);
    }
}

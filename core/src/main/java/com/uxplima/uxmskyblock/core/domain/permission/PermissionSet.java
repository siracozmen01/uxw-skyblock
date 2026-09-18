package com.uxplima.uxmskyblock.core.domain.permission;

import java.util.BitSet;
import java.util.Objects;

/**
 * Compact, immutable bitset representation of compiled permissions for O(1) hot-path testing.
 */
public final class PermissionSet {

    private static final PermissionSet EMPTY = new PermissionSet(new BitSet(0));

    private final BitSet bits;

    private PermissionSet(BitSet bits) {
        this.bits = bits;
    }

    public static PermissionSet empty() {
        return EMPTY;
    }

    public static PermissionSet of(PermissionId... ids) {
        BitSet bs = new BitSet();
        for (PermissionId id : ids) {
            bs.set(id.index());
        }
        return new PermissionSet(bs);
    }

    public static PermissionSet of(Iterable<PermissionId> ids) {
        BitSet bs = new BitSet();
        for (PermissionId id : ids) {
            bs.set(id.index());
        }
        return new PermissionSet(bs);
    }

    public boolean has(PermissionId id) {
        Objects.requireNonNull(id, "id must not be null");
        return bits.get(id.index());
    }

    public PermissionSet with(PermissionId id) {
        Objects.requireNonNull(id, "id must not be null");
        if (bits.get(id.index())) {
            return this;
        }
        BitSet copy = (BitSet) bits.clone();
        copy.set(id.index());
        return new PermissionSet(copy);
    }

    public PermissionSet without(PermissionId id) {
        Objects.requireNonNull(id, "id must not be null");
        if (!bits.get(id.index())) {
            return this;
        }
        BitSet copy = (BitSet) bits.clone();
        copy.clear(id.index());
        return new PermissionSet(copy);
    }

    public PermissionSet union(PermissionSet other) {
        Objects.requireNonNull(other, "other must not be null");
        BitSet copy = (BitSet) bits.clone();
        copy.or(other.bits);
        return new PermissionSet(copy);
    }

    public PermissionSet intersect(PermissionSet other) {
        Objects.requireNonNull(other, "other must not be null");
        BitSet copy = (BitSet) bits.clone();
        copy.and(other.bits);
        return new PermissionSet(copy);
    }

    public boolean isEmpty() {
        return bits.isEmpty();
    }

    public int cardinality() {
        return bits.cardinality();
    }

    public BitSet toBitSetCopy() {
        return (BitSet) bits.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PermissionSet that)) return false;
        return Objects.equals(bits, that.bits);
    }

    @Override
    public int hashCode() {
        return bits.hashCode();
    }

    @Override
    public String toString() {
        return "PermissionSet" + bits.toString();
    }
}

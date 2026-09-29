package com.uxplima.uxmskyblock.core.domain.chunkblock;

import java.util.List;
import java.util.Objects;

/**
 * The island level each chunk beyond the first needs.
 *
 * <p>The level is the currency, and it is held rather than spent: an island keeps a chunk while its
 * level stays at or above what that chunk needed. {@code levels} names the first unlocks one by one;
 * each unlock after them needs {@code thenEvery} more than the one before it.
 */
public record ChunkUnlockRules(List<Long> levels, long thenEvery) {

    public ChunkUnlockRules {
        Objects.requireNonNull(levels, "levels must not be null");
        levels = List.copyOf(levels);
        long previous = 0;
        for (long level : levels) {
            if (level < previous) {
                throw new IllegalArgumentException("unlock levels must not go down: " + levels);
            }
            previous = level;
        }
        if (thenEvery < 1) {
            throw new IllegalArgumentException("then-every must be at least 1: " + thenEvery);
        }
    }

    /** What the plugin ships: one level for the first chunk, three for the second, then five more each. */
    public static ChunkUnlockRules shipped() {
        return new ChunkUnlockRules(List.of(1L, 3L, 6L, 10L, 15L), 5);
    }

    /**
     * The level the {@code n}th chunk beyond the first needs, counting from one.
     *
     * @throws IllegalArgumentException if {@code n} is below one
     */
    public long requiredFor(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("the first unlock is number 1: " + n);
        }
        if (n <= levels.size()) {
            return levels.get(n - 1);
        }
        long last = levels.isEmpty() ? 0 : levels.getLast();
        return last + thenEvery * (long) (n - levels.size());
    }
}

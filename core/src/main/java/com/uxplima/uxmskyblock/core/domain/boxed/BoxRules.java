package com.uxplima.uxmskyblock.core.domain.boxed;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How big a Boxed island's box starts, and how far each advancement its players make pushes it out.
 *
 * <p>An advancement counts once for an island, whoever on it makes it. Most are worth
 * {@code blocksPerAdvancement}; the operator may give any one its own worth, and one of worth 0 grows
 * nothing. An advancement under an ignored prefix, a recipe unlock among them, is worth nothing.
 *
 * @param startRadius how far the box reaches from the island's centre when the island is made
 * @param blocksPerAdvancement how far an advancement with no worth of its own pushes the box out
 * @param maxRadius how far the box can ever reach; keep it inside half the grid spacing
 * @param worth the advancements the operator gave a worth of their own, by key
 * @param ignored the key prefixes of advancements that are worth nothing
 */
public record BoxRules(
        int startRadius, int blocksPerAdvancement, int maxRadius, Map<String, Integer> worth, List<String> ignored) {

    public BoxRules {
        worth = Map.copyOf(worth);
        ignored = List.copyOf(ignored);
        if (startRadius < 1 || maxRadius < startRadius) {
            throw new IllegalArgumentException("the box starts at least 1 across its radius and never shrinks past it");
        }
        if (blocksPerAdvancement < 0 || worth.values().stream().anyMatch(blocks -> blocks < 0)) {
            throw new IllegalArgumentException("an advancement pushes the box out by 0 or more blocks");
        }
    }

    /** What the plugin ships: a box 11 across, one block for each advancement, recipes worth nothing. */
    public static BoxRules shipped() {
        return new BoxRules(5, 1, 400, Map.of(), List.of("minecraft:recipes/"));
    }

    /** How far the advancement pushes the box out. */
    public int blocksFor(String advancement) {
        Objects.requireNonNull(advancement, "advancement must not be null");
        for (String prefix : ignored) {
            if (advancement.startsWith(prefix)) {
                return 0;
            }
        }
        return worth.getOrDefault(advancement, blocksPerAdvancement);
    }

    /** How far the box reaches once its island has earned {@code blocks}. */
    public int radius(long blocks) {
        return (int) Math.min(maxRadius, startRadius + Math.max(0, blocks));
    }
}

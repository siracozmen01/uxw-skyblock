package com.uxplima.uxmskyblock.core.domain.cave;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The ores in a rock, each with the chance that one block of the rock is it.
 *
 * <p>The chances add up in the order they are written, so one roll picks at most one ore, and a rock
 * whose chances add past one is refused.
 */
public record OreTable(List<Ore> ores) {

    public static final OreTable NONE = new OreTable(List.of());

    /**
     * One ore.
     *
     * @param block the block the ore is, by name
     * @param chance the chance that one block of the rock is this ore
     */
    public record Ore(String block, double chance) {

        public Ore {
            Objects.requireNonNull(block, "block must not be null");
            if (block.isBlank() || chance < 0 || chance > 1) {
                throw new IllegalArgumentException("an ore names a block and has a chance between 0 and 1");
            }
        }

        /**
         * An ore written {@code BLOCK:chance}, such as {@code COAL_ORE:0.012}.
         *
         * @throws IllegalArgumentException when it is not written that way
         */
        public static Ore parse(String written) {
            String[] parts = written.trim().split(":", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException(written + " is not BLOCK:chance");
            }
            try {
                return new Ore(parts[0].trim().toUpperCase(Locale.ROOT), Double.parseDouble(parts[1].trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(written + " has no number for a chance", e);
            }
        }
    }

    public OreTable {
        ores = List.copyOf(ores);
        double total = 0;
        for (Ore ore : ores) {
            total += ore.chance();
        }
        if (total > 1) {
            throw new IllegalArgumentException("the ore chances add past one: " + total);
        }
    }

    /** The ore a roll between 0 and 1 lands on, or empty for plain rock. */
    public Optional<String> pick(double roll) {
        double reached = 0;
        for (Ore ore : ores) {
            reached += ore.chance();
            if (roll < reached) {
                return Optional.of(ore.block());
            }
        }
        return Optional.empty();
    }
}

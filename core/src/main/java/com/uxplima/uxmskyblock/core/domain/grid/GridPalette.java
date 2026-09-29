package com.uxplima.uxmskyblock.core.domain.grid;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The blocks a grid is made of, each with a weight: a block of weight 20 stands in the grid twice as
 * often as one of weight 10.
 */
public record GridPalette(List<Entry> entries) {

    /**
     * One block of the palette.
     *
     * @param block the block, by name
     * @param weight how often it stands in the grid, against the others
     */
    public record Entry(String block, int weight) {

        public Entry {
            Objects.requireNonNull(block, "block must not be null");
            if (block.isBlank() || weight < 1) {
                throw new IllegalArgumentException("a grid block names a block and weighs at least 1");
            }
        }

        /**
         * A block written {@code BLOCK:weight}, such as {@code DIRT:40}.
         *
         * @throws IllegalArgumentException when it is not written that way
         */
        public static Entry parse(String written) {
            String[] parts = written.trim().split(":", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException(written + " is not BLOCK:weight");
            }
            try {
                return new Entry(parts[0].trim().toUpperCase(Locale.ROOT), Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(written + " has no whole number for a weight", e);
            }
        }
    }

    public GridPalette {
        entries = List.copyOf(entries);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("a grid needs at least one block");
        }
    }

    /** The sum of every weight. */
    public int totalWeight() {
        int total = 0;
        for (Entry entry : entries) {
            total += entry.weight();
        }
        return total;
    }

    /** The block a roll between 0 and 1 lands on. */
    public String pick(double roll) {
        double target = roll * totalWeight();
        double reached = 0;
        for (Entry entry : entries) {
            reached += entry.weight();
            if (target < reached) {
                return entry.block();
            }
        }
        return entries.get(entries.size() - 1).block();
    }
}

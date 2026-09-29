package com.uxplima.uxmskyblock.bukkit.poseidon;

import java.util.List;
import java.util.Objects;

import org.bukkit.block.data.BlockData;

import org.jspecify.annotations.Nullable;

/**
 * The blocks of a structure template, each at its place inside the template's box.
 *
 * @param sizeX the width of the box
 * @param sizeZ the depth of the box
 * @param pieces every block the template holds
 */
public record WreckTemplate(int sizeX, int sizeZ, List<Piece> pieces) {

    /**
     * One block of a template.
     *
     * @param marker the text of a data marker at this place, which is a note for whoever places the
     *     template rather than a block, or null for a block
     */
    public record Piece(
            int x, int y, int z, BlockData data, @Nullable String marker) {

        public Piece {
            Objects.requireNonNull(data, "data must not be null");
        }
    }

    public WreckTemplate {
        pieces = List.copyOf(pieces);
    }
}

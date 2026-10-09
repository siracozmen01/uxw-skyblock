package com.uxplima.uxmskyblock.core.application.webmap;

import java.util.Objects;

/**
 * How an island is drawn on a web map: the colour of its area by standing, and the words of its tooltip.
 *
 * <p>An operator writes all of it in {@code modules/webmap.conf}. A web map is one page every visitor reads,
 * so its words are in the one language the operator chose rather than each reader's.
 */
public record MarkerLook(Shade top, Shade allied, Shade other, Words words) {

    public MarkerLook {
        Objects.requireNonNull(top, "top must not be null");
        Objects.requireNonNull(allied, "allied must not be null");
        Objects.requireNonNull(other, "other must not be null");
        Objects.requireNonNull(words, "words must not be null");
    }

    /** The shipped look: the palette's butter for the leaders, mint for allies and sky for the rest. */
    public static MarkerLook palette() {
        return new MarkerLook(
                new Shade("#FFE66D", "#FFE66D", 0.35, 3),
                new Shade("#4ECCA3", "#4ECCA3", 0.25, 2),
                new Shade("#48CAE4", "#48CAE4", 0.15, 1),
                Words.english());
    }

    /** One standing's colours: the border, the fill, how much of the map shows through, and the line width. */
    public record Shade(String border, String fill, double opacity, int weight) {

        public Shade {
            Objects.requireNonNull(border, "border must not be null");
            Objects.requireNonNull(fill, "fill must not be null");
            if (opacity < 0.0 || opacity > 1.0) {
                throw new IllegalArgumentException("opacity must be between 0 and 1");
            }
            if (weight < 0) {
                throw new IllegalArgumentException("weight cannot be negative");
            }
        }
    }

    /**
     * The tooltip's words. {@code name} is the label of an island with no name of its own, and carries
     * {@code <island>}, the first eight letters of its id.
     */
    public record Words(
            String name, String owner, String level, String rank, String unranked, String worth, String bank) {

        public Words {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(owner, "owner must not be null");
            Objects.requireNonNull(level, "level must not be null");
            Objects.requireNonNull(rank, "rank must not be null");
            Objects.requireNonNull(unranked, "unranked must not be null");
            Objects.requireNonNull(worth, "worth must not be null");
            Objects.requireNonNull(bank, "bank must not be null");
        }

        public static Words english() {
            return new Words("Island <island>", "Owner", "Level", "Rank", "Unranked", "Net worth", "Bank");
        }

        /** The label of an island with no name of its own. */
        public String nameOf(String islandId) {
            return name.replace("<island>", islandId.substring(0, Math.min(8, islandId.length())));
        }
    }
}

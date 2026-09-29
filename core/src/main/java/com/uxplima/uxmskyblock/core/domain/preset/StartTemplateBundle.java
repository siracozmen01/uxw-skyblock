package com.uxplima.uxmskyblock.core.domain.preset;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;

/**
 * How a preset starts an island in each dimension beyond the first, by dimension id.
 *
 * <p>A dimension is named by an id, never by a closed list: the Nether and the End are two ids among
 * any a server adds, such as {@code myserver:mining_realm}. A dimension the bundle does not name has
 * no start of its own, and nothing is built there.
 */
public record StartTemplateBundle(Map<DimensionId, StartTemplate> templates) {

    /** The action that lays the plain dimension platform, which the shipped bundle uses. */
    public static final String DIMENSION_PLATFORM = "uxm:dimension-platform";

    /** The height a dimension platform stood at before it was the operator's to write. */
    public static final int DEFAULT_HEIGHT = 64;

    public StartTemplateBundle {
        Objects.requireNonNull(templates, "templates must not be null");
        templates = Map.copyOf(new LinkedHashMap<>(templates));
    }

    /** What a preset that writes no dimensions gets: a platform in the Nether and the End. */
    public static StartTemplateBundle shipped() {
        StartTemplate platform = new StartTemplate(List.of(DIMENSION_PLATFORM), DEFAULT_HEIGHT);
        return new StartTemplateBundle(Map.of(DimensionId.THE_NETHER, platform, DimensionId.THE_END, platform));
    }

    /** The start for a dimension, if this bundle names one. */
    public Optional<StartTemplate> resolve(DimensionId dimension) {
        Objects.requireNonNull(dimension, "dimension must not be null");
        return Optional.ofNullable(templates.get(dimension));
    }

    /**
     * Where the island stands in a dimension: its own bounds mirrored one to one, with the height the
     * dimension's template gives. Nothing if the bundle does not name the dimension.
     */
    public Optional<Placement> placeIn(DimensionId dimension, IslandBounds bounds) {
        Objects.requireNonNull(bounds, "bounds must not be null");
        return resolve(dimension).map(template -> new Placement(dimension, bounds, template.height(), template));
    }

    /** An island's place in one dimension, and what builds it there. */
    public record Placement(DimensionId dimension, IslandBounds bounds, int height, StartTemplate template) {

        public Placement {
            Objects.requireNonNull(dimension, "dimension must not be null");
            Objects.requireNonNull(bounds, "bounds must not be null");
            Objects.requireNonNull(template, "template must not be null");
        }

        public int centerX() {
            return bounds.centerX();
        }

        public int centerZ() {
            return bounds.centerZ();
        }
    }
}

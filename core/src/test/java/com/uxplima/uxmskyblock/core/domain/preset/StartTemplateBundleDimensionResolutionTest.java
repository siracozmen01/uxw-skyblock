package com.uxplima.uxmskyblock.core.domain.preset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A start template bundle resolves what to build and where for any dimension id, the server's own as
 * well as the Nether and the End.
 *
 * <p>The game mode architecture names this test. The Nether and End platforms were one method with a
 * switch over three dimensions and a height of 64 written into the travel code, so no preset could
 * start another dimension its own way and no server could add a dimension at all.
 */
class StartTemplateBundleDimensionResolutionTest {

    private static final DimensionId MINING_REALM = DimensionId.of("myserver:mining_realm");

    private final StartTemplateBundle bundle = new StartTemplateBundle(Map.of(
            DimensionId.THE_NETHER,
            new StartTemplate(List.of("uxm:dimension-platform"), 70),
            MINING_REALM,
            new StartTemplate(List.of("myserver:shaft", "uxm:dimension-platform"), 12)));

    @Test
    @DisplayName("A server's own dimension resolves to its own actions, in their order, and its own height")
    void aCustomDimensionResolves() {
        assertThat(bundle.resolve(MINING_REALM)).get().satisfies(template -> {
            assertThat(template.actions()).containsExactly("myserver:shaft", "uxm:dimension-platform");
            assertThat(template.height()).isEqualTo(12);
        });
        assertThat(bundle.resolve(DimensionId.of("  MyServer:Mining_Realm ")))
                .describedAs("a dimension id is read without case or spaces")
                .isPresent();
    }

    @Test
    @DisplayName("A dimension the bundle does not name has no start, so nothing is built there")
    void anUnnamedDimensionHasNoStart() {
        assertThat(bundle.resolve(DimensionId.THE_END)).isEmpty();
        assertThat(bundle.placeIn(DimensionId.of("myserver:sky_realm"), IslandBounds.fromCenterAndRadius(0, 0, 50)))
                .isEmpty();
    }

    @Test
    @DisplayName("An island's place in another dimension mirrors its bounds one to one, at that dimension's height")
    void theBoundsAreMirrored() {
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(5_120, -10_240, 75);

        assertThat(bundle.placeIn(MINING_REALM, bounds)).get().satisfies(placement -> {
            assertThat(placement.dimension()).isEqualTo(MINING_REALM);
            assertThat(placement.bounds()).isEqualTo(bounds);
            assertThat(placement.centerX()).isEqualTo(5_120);
            assertThat(placement.centerZ()).isEqualTo(-10_240);
            assertThat(placement.height()).isEqualTo(12);
        });
        assertThat(bundle.placeIn(IslandDimensionType.NETHER.id(), bounds))
                .get()
                .extracting(StartTemplateBundle.Placement::height)
                .isEqualTo(70);
    }

    @Test
    @DisplayName("The shipped bundle starts the Nether and the End as the plugin always did, and nothing else")
    void theShippedBundleIsTheOldBehaviour() {
        StartTemplateBundle shipped = StartTemplateBundle.shipped();

        for (IslandDimensionType dimension : List.of(IslandDimensionType.NETHER, IslandDimensionType.THE_END)) {
            assertThat(shipped.resolve(dimension.id())).get().satisfies(template -> {
                assertThat(template.actions()).containsExactly(StartTemplateBundle.DIMENSION_PLATFORM);
                assertThat(template.height()).isEqualTo(StartTemplateBundle.DEFAULT_HEIGHT);
            });
        }
        assertThat(shipped.resolve(IslandDimensionType.OVERWORLD.id()))
                .describedAs("the first dimension is the preset's own start")
                .isEmpty();
        assertThat(shipped.resolve(MINING_REALM)).isEmpty();
    }

    @Test
    @DisplayName("A template that builds nothing is refused")
    void anEmptyTemplateIsRefused() {
        assertThatThrownBy(() -> new StartTemplate(List.of(), 64)).isInstanceOf(IllegalArgumentException.class);
    }
}

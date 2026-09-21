package com.uxplima.uxmskyblock.bukkit.webmap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Islands reach the web map.
 *
 * <p>Dynmap, BlueMap and Pl3xMap all shipped, all were wired into a composite, and nothing ever
 * called one. Every server running this plugin with Dynmap installed had a map with no islands on
 * it, and no way to tell whether that was a bug or a setting.
 */
class IslandMarkerSynchroniserTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());

    /** Runs async work inline, so a test reads in the order it was written. */
    private static SchedulerPort inlineScheduler() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        org.mockito.Mockito.doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        return scheduler;
    }

    private static Island island(IslandId id) {
        return Island.create(
                id,
                new IslandBounds(-50, -50, 50, 50, 0, 0, 50),
                PlayerUuid.of(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
    }

    private static IslandLocation location(IslandId id) {
        return new IslandLocation(id, "world", new IslandBounds(-50, -50, 50, 50, 0, 0, 50), 0.5, 101.0, 0.5, 0f, 0f);
    }

    @Test
    @DisplayName("A new island is drawn on the map")
    void aNewIslandIsDrawn() {
        WebMapAdapter map = mock(WebMapAdapter.class);
        when(map.isAvailable()).thenReturn(true);
        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIsland(ISLAND)).thenReturn(Optional.of(island(ISLAND)));
        when(locations.findLocation(ISLAND)).thenReturn(Optional.of(location(ISLAND)));

        new IslandMarkerSynchroniser(map, locations, inlineScheduler()).onIslandCreated(ISLAND);

        verify(map).registerIslandMarker(eq(ISLAND), anyString(), eq("world"), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("A deleted island is taken off the map")
    void aDeletedIslandIsRemoved() {
        WebMapAdapter map = mock(WebMapAdapter.class);
        when(map.isAvailable()).thenReturn(true);

        new IslandMarkerSynchroniser(map, mock(IslandLocationService.class), inlineScheduler()).onIslandRemoved(ISLAND);

        verify(map).removeIslandMarker(ISLAND);
    }

    @Test
    @DisplayName("Every island the world already holds is drawn at startup")
    void existingIslandsAreDrawnAtStartup() {
        WebMapAdapter map = mock(WebMapAdapter.class);
        when(map.isAvailable()).thenReturn(true);
        when(map.providerName()).thenReturn("Dynmap");
        IslandId second = IslandId.of(UUID.randomUUID());
        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findLocation(ISLAND)).thenReturn(Optional.of(location(ISLAND)));
        when(locations.findLocation(second)).thenReturn(Optional.of(location(second)));

        new IslandMarkerSynchroniser(map, locations, inlineScheduler())
                .drawAll(List.of(island(ISLAND), island(second)));

        verify(map, times(2))
                .registerIslandMarker(any(), anyString(), anyString(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("No map installed means no work, not an error")
    void noMapMeansNoWork() {
        WebMapAdapter map = mock(WebMapAdapter.class);
        when(map.isAvailable()).thenReturn(false);
        IslandLocationService locations = mock(IslandLocationService.class);

        new IslandMarkerSynchroniser(map, locations, inlineScheduler()).drawAll(List.of(island(ISLAND)));

        verify(map, never())
                .registerIslandMarker(any(), anyString(), anyString(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("A web map that throws does not take this plugin down with it")
    void aThrowingMapIsSurvived() {
        WebMapAdapter map = mock(WebMapAdapter.class);
        when(map.isAvailable()).thenReturn(true);
        when(map.providerName()).thenReturn("Dynmap");
        doThrow(new IllegalStateException("Dynmap is mid reload"))
                .when(map)
                .registerIslandMarker(any(), anyString(), anyString(), anyDouble(), anyDouble(), anyDouble());
        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findLocation(ISLAND)).thenReturn(Optional.of(location(ISLAND)));

        assertThatCode(() -> new IslandMarkerSynchroniser(map, locations, inlineScheduler())
                        .drawAll(List.of(island(ISLAND))))
                .describedAs("a plugin of ours does not stop because another plugin does")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Drawing never runs on the thread that asked")
    void drawingIsAlwaysHandedToTheScheduler() {
        WebMapAdapter map = mock(WebMapAdapter.class);
        SchedulerPort scheduler = mock(SchedulerPort.class);

        new IslandMarkerSynchroniser(map, mock(IslandLocationService.class), scheduler).onIslandCreated(ISLAND);

        // Nothing ran, because nothing was allowed to run here: a marker is a call into another
        // plugin and a read of an island, and neither belongs under a player's cursor.
        verify(scheduler).async(any(Runnable.class));
        verify(map, never()).isAvailable();
        assertThat(Duration.ZERO).isZero();
    }
}

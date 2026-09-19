package com.uxplima.uxmskyblock.bukkit.webmap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

class WebMapAdaptersTest {

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
    }

    @Test
    @DisplayName("NoOpWebMapAdapter returns NONE and is not available")
    void testNoOpAdapter() {
        NoOpWebMapAdapter adapter = NoOpWebMapAdapter.INSTANCE;
        assertThat(adapter.providerName()).isEqualTo("NONE");
        assertThat(adapter.isAvailable()).isFalse();

        // Safe to call
        IslandId islandId = IslandId.of(UUID.randomUUID());
        adapter.registerIslandMarker(islandId, "Island", "world", 0, 64, 0);
        adapter.updateIslandMarker(islandId, "Island", "world", 0, 64, 0);
        adapter.removeIslandMarker(islandId);
    }

    @Test
    @DisplayName("Providers return correct providerNames")
    void testProviderNames() {
        DynmapAdapter dynmap = new DynmapAdapter(plugin);
        BlueMapAdapter blueMap = new BlueMapAdapter(plugin);
        Pl3xMapAdapter pl3xMap = new Pl3xMapAdapter(plugin);

        assertThat(dynmap.providerName()).isEqualTo("DYNMAP");
        assertThat(blueMap.providerName()).isEqualTo("BLUEMAP");
        assertThat(pl3xMap.providerName()).isEqualTo("PL3XMAP");
    }

    @Test
    @DisplayName("CompositeWebMapAdapter delegates only to available adapters")
    void testCompositeAdapterDelegation() {
        WebMapAdapter adapter1 = mock(WebMapAdapter.class);
        WebMapAdapter adapter2 = mock(WebMapAdapter.class);

        when(adapter1.isAvailable()).thenReturn(true);
        when(adapter1.providerName()).thenReturn("DYNMAP");
        when(adapter2.isAvailable()).thenReturn(false);
        when(adapter2.providerName()).thenReturn("BLUEMAP");

        CompositeWebMapAdapter composite = new CompositeWebMapAdapter(List.of(adapter1, adapter2));

        assertThat(composite.isAvailable()).isTrue();
        assertThat(composite.providerName()).isEqualTo("COMPOSITE (DYNMAP)");

        IslandId islandId = IslandId.of(UUID.randomUUID());
        composite.registerIslandMarker(islandId, "Island", "world", 10.0, 64.0, 10.0);

        verify(adapter1).registerIslandMarker(islandId, "Island", "world", 10.0, 64.0, 10.0);
        verify(adapter2, never()).registerIslandMarker(islandId, "Island", "world", 10.0, 64.0, 10.0);

        composite.updateIslandMarker(islandId, "Island-Renamed", "world", 10.0, 64.0, 10.0);
        verify(adapter1).updateIslandMarker(islandId, "Island-Renamed", "world", 10.0, 64.0, 10.0);

        composite.removeIslandMarker(islandId);
        verify(adapter1).removeIslandMarker(islandId);
    }

    @Test
    @DisplayName("CompositeWebMapAdapter returns NONE when all delegates are unavailable")
    void testCompositeAdapterNoneAvailable() {
        WebMapAdapter adapter1 = mock(WebMapAdapter.class);
        when(adapter1.isAvailable()).thenReturn(false);

        CompositeWebMapAdapter composite = new CompositeWebMapAdapter(List.of(adapter1));

        assertThat(composite.isAvailable()).isFalse();
        assertThat(composite.providerName()).isEqualTo("NONE");
    }
}

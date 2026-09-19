package com.uxplima.uxmskyblock.bukkit.world;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.NamespacedKey;
import org.bukkit.event.world.AsyncStructureSpawnEvent;
import org.bukkit.generator.structure.Structure;

import com.uxplima.uxmskyblock.bukkit.config.WorldConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"deprecation", "removal"})
class AsyncStructureSuppressionListenerTest {

    @Test
    @DisplayName("Cancels structure spawn for suppressed structure types")
    void cancelsSuppressedStructure() {
        WorldConfiguration config = WorldConfiguration.defaultConfiguration();
        AsyncStructureSuppressionListener listener = new AsyncStructureSuppressionListener(config);

        Structure structure = mock(Structure.class);
        when(structure.getKey()).thenReturn(NamespacedKey.minecraft("monument"));

        AsyncStructureSpawnEvent event = mock(AsyncStructureSpawnEvent.class);
        when(event.getStructure()).thenReturn(structure);

        listener.onAsyncStructureSpawn(event);

        verify(event).setCancelled(true);
    }

    @Test
    @DisplayName("Allows structure spawn for non-suppressed structure types")
    void allowsNonSuppressedStructure() {
        WorldConfiguration config = WorldConfiguration.defaultConfiguration();
        AsyncStructureSuppressionListener listener = new AsyncStructureSuppressionListener(config);

        Structure structure = mock(Structure.class);
        when(structure.getKey()).thenReturn(NamespacedKey.minecraft("village"));

        AsyncStructureSpawnEvent event = mock(AsyncStructureSpawnEvent.class);
        when(event.getStructure()).thenReturn(structure);

        listener.onAsyncStructureSpawn(event);

        verify(event, never()).setCancelled(true);
    }
}

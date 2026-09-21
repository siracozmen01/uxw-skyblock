package com.uxplima.uxmskyblock.core.application.island;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.gamemode.GameModeHierarchyService;
import com.uxplima.uxmskyblock.core.application.gamemode.GameModeHierarchyStoragePort;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.world.WorldGridAllocationPort;
import com.uxplima.uxmskyblock.core.application.world.WorldGridPort;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstance;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.world.WorldGridAllocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A new island is bound into its owner's game mode instance.
 *
 * <p>Every island belongs to one instance, and the instance is what a backup names when it says
 * which world the island came from. Nothing ever wrote one, so every backup fell through to an
 * instance id synthesised from the owner's profile: a reference to a row that has never existed.
 */
class NewIslandIsBoundIntoItsHierarchyTest {

    private static final PlayerUuid PLAYER = PlayerUuid.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());
    private static final ServerNodeId NODE = ServerNodeId.of("node-1");

    private static CreateIslandUseCase useCaseWith(
            @org.jspecify.annotations.Nullable GameModeHierarchyService hierarchy, IslandStoragePort storage) {
        WorldGridAllocationPort allocations = mock(WorldGridAllocationPort.class);
        when(allocations.allocateNext(any(), any(), any()))
                .thenReturn(
                        new WorldGridAllocation(0L, "world", 0, 0, Optional.empty(), NODE, java.time.Instant.now()));
        WorldGridPort grid = mock(WorldGridPort.class);
        when(grid.createBounds(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new IslandBounds(-50, -50, 50, 50, 0, 0, 50));
        return new CreateIslandUseCase(
                storage,
                mock(com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort.class),
                mock(IslandBankPort.class),
                new StarterPresetCatalog(),
                grid,
                allocations,
                null,
                hierarchy);
    }

    @Test
    @DisplayName("Creating an island writes the instance and binds the island to it")
    void creatingAnIslandBindsIt() {
        GameModeHierarchyStoragePort hierarchyStorage = mock(GameModeHierarchyStoragePort.class);
        when(hierarchyStorage.findInstanceByProfileId(PROFILE)).thenReturn(Optional.empty());
        GameModeHierarchyService hierarchy = new GameModeHierarchyService(hierarchyStorage);
        IslandStoragePort storage = mock(IslandStoragePort.class);
        when(storage.findIslandIdByProfileId(PROFILE)).thenReturn(Optional.empty());
        CreateIslandUseCase.CreateIslandResult result =
                useCaseWith(hierarchy, storage).execute(PLAYER, PROFILE, "classic", NODE, "world");

        assertThat(result).isInstanceOf(CreateIslandUseCase.CreateIslandResult.Success.class);
        verify(hierarchyStorage).saveGameModeInstance(any(GameModeInstance.class));
        verify(hierarchyStorage).savePrimaryGameplayRootRef(any());
    }

    @Test
    @DisplayName("A hierarchy that will not write never costs the player their island")
    void aFailedBindingStillCreatesTheIsland() {
        GameModeHierarchyStoragePort hierarchyStorage = mock(GameModeHierarchyStoragePort.class);
        when(hierarchyStorage.findInstanceByProfileId(PROFILE)).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("the hierarchy table is gone"))
                .when(hierarchyStorage)
                .saveGameModeInstance(any());
        GameModeHierarchyService hierarchy = new GameModeHierarchyService(hierarchyStorage);
        IslandStoragePort storage = mock(IslandStoragePort.class);
        when(storage.findIslandIdByProfileId(PROFILE)).thenReturn(Optional.empty());
        CreateIslandUseCase.CreateIslandResult result =
                useCaseWith(hierarchy, storage).execute(PLAYER, PROFILE, "classic", NODE, "world");

        assertThat(result)
                .describedAs("the island, its bank and its authority all exist; a hierarchy row does not")
                .isInstanceOf(CreateIslandUseCase.CreateIslandResult.Success.class);
    }

    @Test
    @DisplayName("A caller that brings no hierarchy still creates islands")
    void noHierarchyIsNotAFailure() {
        IslandStoragePort storage = mock(IslandStoragePort.class);
        when(storage.findIslandIdByProfileId(PROFILE)).thenReturn(Optional.empty());
        CreateIslandUseCase.CreateIslandResult result =
                useCaseWith(null, storage).execute(PLAYER, PROFILE, "classic", NODE, "world");

        assertThat(result).isInstanceOf(CreateIslandUseCase.CreateIslandResult.Success.class);
    }

    @Test
    @DisplayName("The island a caller already has is returned rather than a second one created")
    void anExistingIslandIsReturned() {
        IslandId existing = IslandId.of(UUID.randomUUID());
        IslandStoragePort storage = mock(IslandStoragePort.class);
        when(storage.findIslandIdByProfileId(PROFILE)).thenReturn(Optional.of(existing));
        CreateIslandUseCase.CreateIslandResult result =
                useCaseWith(null, storage).execute(PLAYER, PROFILE, "classic", NODE, "world");

        assertThat(result).isEqualTo(new CreateIslandUseCase.CreateIslandResult.AlreadyHasIsland(existing));
    }
}

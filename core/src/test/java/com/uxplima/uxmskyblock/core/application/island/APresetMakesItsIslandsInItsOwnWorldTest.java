package com.uxplima.uxmskyblock.core.application.island;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.world.WorldGridAllocationPort;
import com.uxplima.uxmskyblock.core.application.world.WorldGridPort;
import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthoritySweep;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.preset.StartTemplateBundle;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.world.WorldGridAllocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A preset may name the world its islands are made in, and those islands are then made, placed and kept
 * alive there.
 *
 * <p>Every island was made in the server's one island world. A mode played on land of its own, Boxed
 * on generated terrain, needs a world the server makes the usual way, and an island there needs its
 * grid slot, its location and its authority lease in that world too.
 */
class APresetMakesItsIslandsInItsOwnWorldTest {

    private static final PlayerUuid PLAYER = PlayerUuid.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());
    private static final ServerNodeId NODE = ServerNodeId.of("node-1");

    @Test
    @DisplayName("An island of a preset that names a world takes its grid slot and its location there")
    void theIslandIsMadeInThePresetsWorld() {
        WorldGridAllocationPort allocations = mock(WorldGridAllocationPort.class);
        when(allocations.allocateNext(any(), anyString(), any()))
                .thenAnswer(call ->
                        new WorldGridAllocation(0L, call.getArgument(1), 0, 0, Optional.empty(), NODE, Instant.now()));
        WorldGridPort grid = mock(WorldGridPort.class);
        when(grid.createBounds(any(), anyInt())).thenReturn(new IslandBounds(-50, -50, 50, 50, 0, 0, 50));
        IslandStoragePort storage = mock(IslandStoragePort.class);
        when(storage.findIslandIdByProfileId(PROFILE)).thenReturn(Optional.empty());
        StarterPreset boxed = new StarterPreset(
                "boxed",
                "@presets.boxed.name",
                "@presets.boxed.description",
                "schematics/boxed.schem",
                IslandBiome.PLAINS,
                GameModeType.SKYBLOCK,
                List.of("uxm:platform"),
                StartTemplateBundle.shipped(),
                " boxed_world ");
        CreateIslandUseCase useCase = new CreateIslandUseCase(
                storage,
                mock(IslandAuthorityPort.class),
                mock(IslandBankPort.class),
                new StarterPresetCatalog(List.of(StarterPresetCatalog.CLASSIC, boxed), "classic"),
                grid,
                allocations,
                null,
                null);

        CreateIslandUseCase.CreateIslandResult made = useCase.execute(PLAYER, PROFILE, "boxed", NODE, "skyblock");

        assertThat(made)
                .isInstanceOfSatisfying(
                        CreateIslandUseCase.CreateIslandResult.Success.class,
                        success -> assertThat(success.location().worldName()).isEqualTo("boxed_world"));
        verify(allocations).allocateNext(eq(NODE), eq("boxed_world"), any());
        assertThat(StarterPresetCatalog.CLASSIC.worldOr("skyblock"))
                .describedAs("a preset that names no world makes its islands in the server's own")
                .isEqualTo("skyblock");
    }

    @Test
    @DisplayName("The authority heartbeat keeps islands alive in every world, one failing world leaving the rest")
    void everyWorldIsSwept() {
        List<String> swept = new ArrayList<>();
        IslandAuthorityPort port = mock(IslandAuthorityPort.class);
        when(port.sweepAuthority(eq(NODE), anyString(), anyInt())).thenAnswer(call -> {
            String world = call.getArgument(1);
            swept.add(world);
            if (world.equals("broken")) {
                throw new IllegalStateException("that world's rows cannot be read");
            }
            return new IslandAuthoritySweep(2, 0, 1);
        });

        IslandAuthoritySweep total = new IslandAuthorityService(
                        port, NODE, List.of("skyblock", "broken", "boxed_world"), Duration.ofMinutes(10))
                .heartbeat();

        assertThat(swept).containsExactly("skyblock", "broken", "boxed_world");
        assertThat(total).isEqualTo(new IslandAuthoritySweep(4, 0, 2));
    }
}

package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.player.PlayerTeleportEvent;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.entity.TeleportFlag;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is home} puts a player above whatever has grown over their island's spawn.
 *
 * <p>The spawn is stored once. The classic island's sapling stood on it and grew into a tree, and a
 * bot on a live server was sent home into the trunk and suffocated. An island made before the
 * sapling moved still has that tree, so the landing is found when the player is sent.
 */
class HomeLandsAboveWhatGrewOnTheSpawnTest extends MockBukkitHarness {

    private static final String WORLD = "islands";

    @Test
    @DisplayName("A home under a grown trunk lands on top of it")
    void aBuriedHomeLandsAbove() throws Exception {
        World world = server.addSimpleWorld(WORLD);
        for (int y = 65; y <= 69; y++) {
            world.getBlockAt(8, y, 8).setType(Material.OAK_LOG);
        }
        Arriving player = new Arriving(server);
        server.addPlayer(player);
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.resolveHome(profile))
                .thenReturn(Optional.of(new IslandLocation(
                        IslandId.of(UUID.randomUUID()),
                        WORLD,
                        IslandBounds.fromCenterAndRadius(0, 0, 50),
                        8.5,
                        65.0,
                        8.5,
                        0.0f,
                        0.0f)));
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(profile));
        IslandNavigationCommands commands = new IslandNavigationCommands(
                locations,
                sessions,
                new InlineSchedulerPort(),
                WORLD,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()));
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildHome());
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);

        dispatcher.execute("home", source);

        assertThat(player.arrivedAt).describedAs("where the player was sent").isNotNull();
        assertThat(java.util.Objects.requireNonNull(player.arrivedAt).getY())
                .describedAs("the first open blocks above the trunk")
                .isEqualTo(70.0);
    }

    /** A player whose teleport is recorded, which MockBukkit does not carry out. */
    private static final class Arriving extends PlayerMock {
        private @Nullable Location arrivedAt;

        Arriving(ServerMock server) {
            super(server, "Arriving", UUID.randomUUID());
        }

        @Override
        public CompletableFuture<Boolean> teleportAsync(
                Location location, PlayerTeleportEvent.TeleportCause cause, TeleportFlag... flags) {
            this.arrivedAt = location;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Boolean> teleportAsync(Location location, PlayerTeleportEvent.TeleportCause cause) {
            return teleportAsync(location, cause, new TeleportFlag[0]);
        }
    }
}

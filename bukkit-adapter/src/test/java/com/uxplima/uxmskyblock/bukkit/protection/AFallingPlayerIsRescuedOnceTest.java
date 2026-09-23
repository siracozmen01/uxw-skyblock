package com.uxplima.uxmskyblock.bukkit.protection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import io.papermc.paper.entity.TeleportFlag;

import com.uxplima.uxmskyblock.bukkit.config.ProtectionConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.SettingsConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A player who falls into the void is sent home once per fall.
 *
 * <p>The move is asynchronous, so the player keeps falling for a tick or more after it is asked
 * for, and every one of those moves and void hits asked again: a second teleport, a second message
 * and a second pair of shields for the same fall. A fall is rescued once, and the next fall after
 * the player has landed is rescued again.
 */
@SuppressWarnings({"deprecation", "removal"})
class AFallingPlayerIsRescuedOnceTest extends MockBukkitHarness {

    private SlowTeleportPlayer player;
    private World world;
    private VoidProtectionListener listener;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("skyblock_world");
        player = new SlowTeleportPlayer(server);
        server.addPlayer(player);
        Island island = Island.create(
                new IslandId(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(player.getUniqueId()),
                new ProfileId(player.getUniqueId()),
                Instant.now());
        listener = new VoidProtectionListener(
                ProtectionConfiguration.defaultConfiguration(),
                SettingsConfiguration.defaultConfiguration(),
                loc -> Optional.of(island),
                java.time.Clock.systemUTC(),
                Messages.bundled());
    }

    @Test
    @DisplayName("Moves and void hits while the move home is under way ask for nothing more")
    void oneFallOneRescue() {
        for (int tick = 0; tick < 5; tick++) {
            fallOneTick();
        }

        assertThat(player.moves).describedAs("teleports asked for one fall").hasSize(1);
    }

    @Test
    @DisplayName("Every void hit during the fall is still cancelled")
    void theVoidNeverLandsAHit() {
        fallOneTick();
        EntityDamageEvent secondHit = new EntityDamageEvent(player, DamageCause.VOID, 4.0);
        listener.onVoidDamage(secondHit);

        assertThat(secondHit.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("Once the player has landed, the next fall is rescued again")
    void theNextFallIsRescued() {
        fallOneTick();
        player.moves.get(0).complete(true);

        fallOneTick();

        assertThat(player.moves).describedAs("teleports asked for two falls").hasSize(2);
    }

    private void fallOneTick() {
        Location from = new Location(world, 0.5, -70, 0.5);
        Location to = new Location(world, 0.5, -71, 0.5);
        listener.onPlayerMove(new PlayerMoveEvent(player, from, to));
        listener.onVoidDamage(new EntityDamageEvent(player, DamageCause.VOID, 4.0));
    }

    /** A player whose move home takes as long as the test says. */
    private static final class SlowTeleportPlayer extends PlayerMock {

        private final List<CompletableFuture<Boolean>> moves = new ArrayList<>();

        SlowTeleportPlayer(ServerMock server) {
            super(server, "Faller", UUID.randomUUID());
        }

        @Override
        public CompletableFuture<Boolean> teleportAsync(Location location, TeleportCause cause, TeleportFlag... flags) {
            CompletableFuture<Boolean> move = new CompletableFuture<>();
            moves.add(move);
            return move;
        }
    }
}

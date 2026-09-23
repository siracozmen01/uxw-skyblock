package com.uxplima.uxmskyblock.bukkit.protection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
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
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * A void rescue reads the island's surface only once the player stands over it.
 *
 * <p>On Folia a chunk is read only by the region that owns it. The rescue ran on the falling
 * player's region and read the height of the island's centre, which on an island grown past the
 * player's view is another region's chunk or no loaded chunk at all. Folia throws there, and a
 * player who fell off the far side of a large island fell to their death with the rescue half done.
 * The world here answers the way Folia does: a column is read only under the player.
 */
@SuppressWarnings({"deprecation", "removal"})
class AVoidRescueReadsOnlyWhereThePlayerIsTest extends MockBukkitHarness {

    private static final int CENTRE = 100;

    private MovingPlayer player;
    private RegionOwnedWorld world;
    private VoidProtectionListener listener;

    @BeforeEach
    void setUp() {
        world = new RegionOwnedWorld();
        world.setName("skyblock_world");
        server.addWorld(world);
        player = new MovingPlayer(server);
        server.addPlayer(player);
        world.player = player;
        world.getBlockAt(CENTRE, 80, CENTRE).setType(Material.STONE);

        Island island = Island.create(
                new IslandId(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(CENTRE, CENTRE, 250),
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
    @DisplayName("A player falling far from the island's centre lands on its surface")
    void theRescueLandsOnTheSurface() {
        player.setLocation(new Location(world, CENTRE + 200.5, -70, CENTRE + 200.5));

        EntityDamageEvent hit = new EntityDamageEvent(player, DamageCause.VOID, 4.0);
        listener.onVoidDamage(hit);

        assertThat(hit.isCancelled()).isTrue();
        assertThat(world.readElsewhere)
                .describedAs("columns read away from the player")
                .isZero();
        assertThat(player.getLocation().getBlockX()).isEqualTo(CENTRE);
        assertThat(player.getLocation().getBlockZ()).isEqualTo(CENTRE);
        assertThat(player.getLocation().getBlockY())
                .describedAs("standing on the highest block of the centre")
                .isEqualTo(81);
    }

    /** A world whose columns are read only in the chunk the player stands in. */
    private static final class RegionOwnedWorld extends WorldMock {

        private @org.jspecify.annotations.Nullable PlayerMock player;
        private int readElsewhere;

        @Override
        public int getHighestBlockYAt(int x, int z) {
            Location at = java.util.Objects.requireNonNull(player).getLocation();
            if ((at.getBlockX() >> 4) != (x >> 4) || (at.getBlockZ() >> 4) != (z >> 4)) {
                readElsewhere++;
                throw new IllegalStateException("Cannot read a chunk another region owns");
            }
            return super.getHighestBlockYAt(x, z);
        }
    }

    /** A player whose asynchronous move lands at once. */
    private static final class MovingPlayer extends PlayerMock {

        MovingPlayer(ServerMock server) {
            super(server, "Faller", UUID.randomUUID());
        }

        @Override
        public CompletableFuture<Boolean> teleportAsync(Location location, TeleportCause cause, TeleportFlag... flags) {
            setLocation(location);
            return CompletableFuture.completedFuture(true);
        }
    }
}

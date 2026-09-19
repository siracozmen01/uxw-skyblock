package com.uxplima.uxmskyblock.bukkit.ward;

import static org.assertj.core.api.Assertions.assertThat;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Zombie;
import org.bukkit.event.player.PlayerTeleportEvent;

import com.uxplima.uxmskyblock.bukkit.config.ProtectionConfiguration;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.ward.KineticWardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class KineticWardListenerTest extends MockBukkitHarness {

    private World world;
    private PlayerMock player;
    private ProtectionConfiguration config;
    private KineticWardService wardService;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("skyblock_world");
        player = createPlayer("Hero");
        config = ProtectionConfiguration.defaultConfiguration();
        wardService = new KineticWardService(
                config.kineticWardRadius(), config.kineticWardForce(), config.kineticWardVerticalLift());
    }

    @Test
    @DisplayName("Applies outward kinetic impulse to nearby hostile monsters upon teleport arrival")
    void repelsNearbyMonstersOnTeleport() {
        KineticWardListener listener = new KineticWardListener(config, wardService);

        Location destination = new Location(world, 0, 64, 0);
        // Spawn a monster within the 5m radius (at 2, 64, 0)
        Location monsterLoc = new Location(world, 2, 64, 0);
        Zombie zombie = world.spawn(monsterLoc, Zombie.class);

        PlayerTeleportEvent event = new PlayerTeleportEvent(
                player, new Location(world, 100, 64, 100), destination, PlayerTeleportEvent.TeleportCause.COMMAND);

        listener.onPlayerTeleport(event);

        // Repulsion pushes zombie outwards in +X direction and lifts upwards
        assertThat(zombie.getVelocity().getX()).isGreaterThan(0.0);
        assertThat(zombie.getVelocity().getY()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("Ignores teleport when kinetic ward is disabled in configuration")
    void doesNothingWhenDisabled() {
        ProtectionConfiguration disabledConfig = new ProtectionConfiguration(
                true,
                true,
                java.time.Duration.ofSeconds(60),
                true,
                -64,
                java.time.Duration.ofSeconds(10),
                false,
                5.0,
                1.5,
                0.35);
        KineticWardListener listener = new KineticWardListener(disabledConfig, wardService);

        Location destination = new Location(world, 0, 64, 0);
        Zombie zombie = world.spawn(new Location(world, 2, 64, 0), Zombie.class);

        PlayerTeleportEvent event = new PlayerTeleportEvent(
                player, new Location(world, 100, 64, 100), destination, PlayerTeleportEvent.TeleportCause.COMMAND);

        listener.onPlayerTeleport(event);

        // Velocity unchanged (zero x and z)
        assertThat(zombie.getVelocity().getX()).isZero();
        assertThat(zombie.getVelocity().getZ()).isZero();
    }
}

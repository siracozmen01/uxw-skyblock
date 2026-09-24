package com.uxplima.uxmskyblock.bukkit.navigation;

import static org.assertj.core.api.Assertions.assertThat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A player sent to a spawn something has grown over arrives above it, not inside it.
 *
 * <p>The classic island's sapling stood on the centre, which is the spawn, and grew into a tree. A bot
 * on a live server was sent home into the trunk and suffocated. The spawn is stored once, so an island
 * made before the sapling moved still has one; the landing is found where the player arrives.
 */
class AGrownTreeDoesNotBuryTheSpawnTest extends MockBukkitHarness {

    private World world;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("landing");
        world.getBlockAt(0, 99, 0).setType(Material.GRASS_BLOCK);
    }

    @Test
    @DisplayName("A spawn inside a trunk is raised to the first two open blocks above it")
    void aBuriedSpawnIsRaised() {
        for (int y = 100; y <= 104; y++) {
            world.getBlockAt(0, y, 0).setType(Material.OAK_LOG);
        }

        Location landing = SafeLanding.clear(new Location(world, 0.5, 100.0, 0.5, 90.0f, 10.0f));

        assertThat(landing.getY()).isEqualTo(105.0);
        assertThat(landing.getX()).isEqualTo(0.5);
        assertThat(landing.getYaw())
                .describedAs("the way the player faces is kept")
                .isEqualTo(90.0f);
    }

    @Test
    @DisplayName("An open spawn is where the player arrives, as it always was")
    void anOpenSpawnIsKept() {
        Location spawn = new Location(world, 0.5, 100.0, 0.5);

        assertThat(SafeLanding.clear(spawn)).isEqualTo(spawn);
    }

    @Test
    @DisplayName("A spawn with only its head covered is raised too")
    void aCoveredHeadIsRaised() {
        world.getBlockAt(0, 101, 0).setType(Material.OAK_LEAVES);

        assertThat(SafeLanding.clear(new Location(world, 0.5, 100.0, 0.5)).getY())
                .isEqualTo(102.0);
    }

    @Test
    @DisplayName("A spawn buried deeper than it looks is left as it is rather than guessed at")
    void aDeepSpawnIsLeft() {
        for (int y = 100; y <= 100 + SafeLanding.MAX_RISE + 2; y++) {
            world.getBlockAt(0, y, 0).setType(Material.STONE);
        }
        Location spawn = new Location(world, 0.5, 100.0, 0.5);

        assertThat(SafeLanding.clear(spawn)).isEqualTo(spawn);
    }
}

package com.uxplima.uxmskyblock.bukkit.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A profile switch writes the profile it leaves as part of the switch, not at the next checkpoint.
 *
 * <p>The testing standard names this test. The checkpoint here runs once an hour; by the time the
 * switch is done the leaving profile holds what the player had on it.
 */
class ProfileSwitchDoesNotWaitForAmbientCheckpointTest extends MockBukkitHarness {

    private SessionBench bench;

    @BeforeEach
    void setUpBench() throws Exception {
        bench = new SessionBench(server);
    }

    @AfterEach
    void tearDownBench() {
        bench.close();
    }

    @Test
    @DisplayName("The switch has written the leaving profile by the time it completes")
    void theSwitchWritesAtOnce() {
        PlayerMock player = bench.inPlay(createPlayer("Switcher"));
        ProfileId leaving = Objects.requireNonNull(bench.coordinator.getActiveSession(player.getUniqueId()))
                .activeProfileId();
        ProfileId next = new ProfileId(UUID.randomUUID());
        bench.persistence.registerProfile(new PlayerUuid(player.getUniqueId()), next);
        bench.persistence
                .inventoryPort()
                .initializeInventory(ProfileInventoryRecord.createDefault(next, new byte[0], new byte[0]));
        long versionBefore = bench.stored(leaving).version();
        player.getInventory().setItem(0, new ItemStack(Material.LAPIS_BLOCK, 7));

        bench.coordinator.switchProfile(player, next);

        bench.until(() -> assertThat(Objects.requireNonNull(bench.coordinator.getActiveSession(player.getUniqueId()))
                        .activeProfileId())
                .isEqualTo(next));
        assertThat(bench.stored(leaving).version()).isGreaterThan(versionBefore);
        assertThat(SessionBench.items(bench.stored(leaving))).containsExactly(new ItemStack(Material.LAPIS_BLOCK, 7));
    }
}

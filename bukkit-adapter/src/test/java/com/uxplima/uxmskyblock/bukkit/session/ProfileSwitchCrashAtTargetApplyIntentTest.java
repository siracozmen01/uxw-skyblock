package com.uxplima.uxmskyblock.bukkit.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.profile.SwitchProfileUseCase;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileSwitchOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A switch the server died in after its apply intent is rolled forward when the player comes back.
 *
 * <p>The testing standard names this test. The switch has reached its apply intent: the leaving
 * profile is written, the target is loaded and the intent committed, and the player was never given
 * the target. The server dies. When the player joins again, on a server that knows nothing of the
 * switch, the switch is committed, the target profile is the one in play and the player holds its
 * items, whatever the game's own player file put in their hands first. The leaving profile keeps what
 * it had.
 */
class ProfileSwitchCrashAtTargetApplyIntentTest extends MockBukkitHarness {

    private SessionBench before;
    private @org.jspecify.annotations.Nullable SessionBench after;

    @BeforeEach
    void setUpBench() throws Exception {
        before = new SessionBench(server);
    }

    @AfterEach
    void tearDownBench() {
        SessionBench restarted = after;
        if (restarted != null) {
            restarted.close();
        }
        before.close();
    }

    @Test
    @DisplayName("After a crash at the apply intent the player comes back on the target profile with its items")
    void theSwitchRollsForward() {
        PlayerMock player = before.inPlay(createPlayer("Crasher"));
        PlayerUuid uuid = new PlayerUuid(player.getUniqueId());
        ActiveSession session = Objects.requireNonNull(before.coordinator.getActiveSession(player.getUniqueId()));
        ProfileId leaving = session.activeProfileId();
        ProfileId target = new ProfileId(UUID.randomUUID());
        before.persistence.registerProfile(uuid, target);
        ItemStack[] targetItems = {new ItemStack(Material.EMERALD, 12)};
        before.persistence
                .inventoryPort()
                .initializeInventory(ProfileInventoryRecord.createDefault(
                        target, BukkitInventorySerializer.serializeItemStacks(targetItems), new byte[0]));
        player.getInventory().setItem(0, new ItemStack(Material.NETHERITE_INGOT, 2));

        SwitchProfileUseCase switches =
                new SwitchProfileUseCase(before.persistence.profileSwitchPort(), before.persistence.inventoryPort());
        assertThat(switches.prepareSwitch(
                                UUID.randomUUID(),
                                uuid,
                                leaving,
                                target,
                                before.coordinator.nodeId(),
                                session.sessionEpoch(),
                                BukkitInventorySerializer.snapshotPlayer(player, leaving, session.lastDurableVersion()))
                        .isOk())
                .isTrue();
        assertThat(before.persistence.profileSwitchPort().findActiveOperation(uuid))
                .get()
                .isInstanceOf(ProfileSwitchOperation.TargetApplyIntent.class);

        // The server dies here. What the game's own player file holds is in the player's hands.
        player.getInventory().clear();
        player.getInventory().setItem(0, new ItemStack(Material.DIRT, 64));
        SessionBench restarted = new SessionBench(server, before.persistence);
        after = restarted;
        restarted.inPlay(player);

        assertThat(Objects.requireNonNull(restarted.coordinator.getActiveSession(player.getUniqueId()))
                        .activeProfileId())
                .isEqualTo(target);
        assertThat(player.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.EMERALD, 12));
        assertThat(player.getInventory().contains(Material.DIRT)).isFalse();
        assertThat(player.getInventory().contains(Material.NETHERITE_INGOT)).isFalse();
        assertThat(SessionBench.items(restarted.stored(leaving)))
                .describedAs("the leaving profile keeps what it had")
                .containsExactly(new ItemStack(Material.NETHERITE_INGOT, 2));
        assertThat(after.persistence.profileSwitchPort().findActiveOperation(uuid))
                .isEmpty();
    }
}

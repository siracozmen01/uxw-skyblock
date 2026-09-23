package com.uxplima.uxmskyblock.bukkit.antiabuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A reset that finished while its player was away empties their inventory when they come back.
 *
 * <p>The purge ran on the player's own thread when the erasure finished, so a player who left in
 * the meantime kept everything they carried into their next island.
 */
class ResetInventoryPurgeTest extends MockBukkitHarness {

    private PlayerMock player;
    private IslandAntiAbuseService antiAbuse;
    private ResetInventoryPurge purge;

    @BeforeEach
    void setUp() {
        player = createPlayer("Returner");
        player.getInventory().addItem(new ItemStack(Material.DIAMOND_BLOCK, 64));
        antiAbuse = mock(IslandAntiAbuseService.class);
        SchedulerPort inline = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(inline)
                .async(any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(inline)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        purge = new ResetInventoryPurge(antiAbuse, inline);
    }

    @Test
    @DisplayName("A player who owes a purge has their inventory emptied when their session is made")
    void anOwedPurgeIsPaid() {
        when(antiAbuse.isInventoryPurgeOwed(new PlayerUuid(player.getUniqueId())))
                .thenReturn(true);

        purge.onSessionActive(player);

        assertThat(player.getInventory().isEmpty()).isTrue();
        verify(antiAbuse).settleInventoryPurge(new PlayerUuid(player.getUniqueId()));
    }

    @Test
    @DisplayName("A player who owes nothing keeps what they carry")
    void nothingOwedTouchesNothing() {
        when(antiAbuse.isInventoryPurgeOwed(new PlayerUuid(player.getUniqueId())))
                .thenReturn(false);

        purge.onSessionActive(player);

        assertThat(player.getInventory().contains(Material.DIAMOND_BLOCK, 64)).isTrue();
        verify(antiAbuse, never()).settleInventoryPurge(any());
    }
}

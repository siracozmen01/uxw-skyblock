package com.uxplima.uxmskyblock.bukkit.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Material;
import org.bukkit.inventory.PlayerInventory;

import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryMutationJournalPort;
import com.uxplima.uxmskyblock.core.application.reward.RewardDeliveryHandler.DeliveryResult;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentOperationId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentState;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantState;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * An item reward is read and put into the inventory on the thread that owns the player.
 *
 * <p>A claim runs on an asynchronous thread, and the item handler read and changed the inventory
 * from there. On Folia the player's region owns that inventory and on Paper the main thread does,
 * so the delivery raced the player's own clicks and its undo read slots that were already moving.
 * Here the player has a thread of their own, and every look at their inventory from anywhere else is
 * counted.
 */
class AnItemRewardIsGivenOnThePlayersThreadTest {

    private ServerMock server;
    private ExecutorService playersThread;
    private OwnThreadPlayer player;
    private PlayerSessionCoordinator sessions;
    private InventoryMutationJournalPort journal;
    private final ProfileId profile = new ProfileId(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        playersThread = Executors.newSingleThreadExecutor(r -> new Thread(r, "the-players-region"));
        player = new OwnThreadPlayer(server);
        server.addPlayer(player);
        sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(profile));
        when(sessions.getActiveSession(player.getUniqueId()))
                .thenReturn(new ActiveSession(new PlayerUuid(player.getUniqueId()), profile, 1L, 1L));
        journal = mock(InventoryMutationJournalPort.class);
        when(journal.recordIntent(any(), any(), any(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(InventoryMutationJournalOutcome.intentRecorded());
        when(journal.commitMutation(any(), any(), any(), anyLong(), anyLong(), any(), any()))
                .thenReturn(InventoryMutationJournalOutcome.success(2L));
    }

    @AfterEach
    void tearDown() {
        playersThread.shutdownNow();
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("A claim on an asynchronous thread gives the item on the player's thread and nowhere else")
    void theInventoryIsOnlyTouchedByItsOwner() {
        ItemRewardDeliveryHandler handler =
                new ItemRewardDeliveryHandler(sessions, journal, new ServerNodeId("node"), new OwnThreadScheduler());
        player.touchedElsewhere.set(0);

        DeliveryResult result = handler.deliver(grant(), diamonds(), profile);

        assertThat(result.success()).isTrue();
        assertThat(player.touchedElsewhere)
                .describedAs("looks at the inventory from a thread that does not own the player")
                .hasValue(0);
        assertThat(player.touchedByOwner.get())
                .describedAs("looks at the inventory from its own thread")
                .isPositive();
        assertThat(player.getInventory().contains(Material.DIAMOND, 5)).isTrue();
    }

    @Test
    @DisplayName("A player who left before their thread got to the item keeps it in the inbox")
    void aPlayerWhoLeftGetsNothingNow() {
        SchedulerPort gone = new OwnThreadScheduler() {
            @Override
            public void onEntity(PlayerUuid playerUuid, Runnable task, Runnable retired) {
                retired.run();
            }
        };
        ItemRewardDeliveryHandler handler =
                new ItemRewardDeliveryHandler(sessions, journal, new ServerNodeId("node"), gone);

        DeliveryResult result = handler.deliver(grant(), diamonds(), profile);

        assertThat(result.success()).isFalse();
        assertThat(player.getInventory().contains(Material.DIAMOND)).isFalse();
    }

    private RewardGrant grant() {
        return new RewardGrant(
                new RewardGrantId(UUID.randomUUID()),
                profile,
                "QUEST_REWARD",
                "quest-1",
                RewardGrantState.CLAIMING,
                List.of(),
                null,
                null,
                Instant.now(),
                Instant.now());
    }

    private static RewardGrantComponent diamonds() {
        return new RewardGrantComponent(
                UUID.randomUUID(),
                new RewardGrantId(UUID.randomUUID()),
                0,
                new RewardComponentOperationId(UUID.randomUUID()),
                RewardComponentType.ITEM,
                "uxm:item_bundle",
                1,
                "{\"item\":\"DIAMOND\",\"amount\":5}",
                RewardComponentState.PENDING,
                null,
                Instant.now());
    }

    /** A player whose inventory counts who looks at it. */
    private static final class OwnThreadPlayer extends PlayerMock {

        private final AtomicInteger touchedElsewhere = new AtomicInteger();
        private final AtomicInteger touchedByOwner = new AtomicInteger();

        OwnThreadPlayer(ServerMock server) {
            super(server, "Claimer", UUID.randomUUID());
        }

        @Override
        public PlayerInventory getInventory() {
            if (Thread.currentThread().getName().equals("the-players-region")) {
                touchedByOwner.incrementAndGet();
            } else {
                touchedElsewhere.incrementAndGet();
            }
            return super.getInventory();
        }
    }

    /** Runs a player's work on the player's own thread, the way Folia's entity scheduler does. */
    private class OwnThreadScheduler implements SchedulerPort {

        @Override
        public boolean ownsEntity(PlayerUuid playerUuid) {
            return Thread.currentThread().getName().equals("the-players-region");
        }

        @Override
        public void onEntity(PlayerUuid playerUuid, Runnable task) {
            playersThread.execute(task);
        }

        @Override
        public void onEntity(PlayerUuid playerUuid, Runnable task, Runnable retired) {
            playersThread.execute(task);
        }

        @Override
        public void onGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {
            task.run();
        }

        @Override
        public void async(Runnable task) {
            task.run();
        }

        @Override
        public void asyncAfter(Duration delay, Runnable task) {
            task.run();
        }

        @Override
        public void laterGlobal(Duration delay, Runnable task) {
            task.run();
        }

        @Override
        public AutoCloseable repeatGlobal(Runnable task, Duration initialDelay, Duration period) {
            return () -> {};
        }

        @Override
        public AutoCloseable repeatAsync(Runnable task, Duration initialDelay, Duration period) {
            return () -> {};
        }
    }
}

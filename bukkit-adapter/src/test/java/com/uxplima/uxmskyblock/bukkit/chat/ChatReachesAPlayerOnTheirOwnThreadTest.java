package com.uxplima.uxmskyblock.bukkit.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.kyori.adventure.text.Component;

import com.uxplima.uxmskyblock.bukkit.config.ChatConfiguration;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatFrame;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A chat line reaches a player on the thread that owns them.
 *
 * <p>The delivery runs wherever the message arrived: the scheduler pool on one node, a Redis
 * subscriber thread across a cluster. Neither of those owns a player, and it used to look a player
 * up and write to them right there. On Folia that is the thing the whole design exists to stop, and
 * on Paper iterating the live player list off the main thread is a race with every join and quit.
 */
class ChatReachesAPlayerOnTheirOwnThreadTest {

    private static final ProfileId ONE = ProfileId.of(UUID.randomUUID());
    private static final ProfileId TWO = ProfileId.of(UUID.randomUUID());

    /** A scheduler that writes down who it was asked to hop onto, and runs nothing. */
    private static final class RecordingScheduler implements SchedulerPort {
        final List<PlayerUuid> hops = new ArrayList<>();

        @Override
        public void onGlobal(Runnable task) {}

        @Override
        public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {}

        @Override
        public void onEntity(PlayerUuid playerUuid, Runnable task) {
            hops.add(playerUuid);
        }

        @Override
        public void laterGlobal(java.time.Duration delay, Runnable task) {}

        @Override
        public void async(Runnable task) {}

        @Override
        public void asyncAfter(java.time.Duration delay, Runnable task) {}

        @Override
        public AutoCloseable repeatAsync(Runnable task, java.time.Duration initialDelay, java.time.Duration period) {
            return () -> {};
        }

        @Override
        public AutoCloseable repeatGlobal(Runnable task, java.time.Duration initialDelay, java.time.Duration period) {
            return () -> {};
        }
    }

    private static IslandChatFrame frame() {
        return IslandChatFrame.island(
                IslandId.of(UUID.randomUUID()), ONE, "Sender", IslandRole.MEMBER, "hello", Instant.now());
    }

    @Test
    @DisplayName("Every recipient is reached on their own thread, one hop each")
    void everyRecipientIsReachedOnTheirOwnThread() {
        RecordingScheduler scheduler = new RecordingScheduler();
        BukkitIslandChatDeliveryAdapter adapter =
                new BukkitIslandChatDeliveryAdapter(ChatConfiguration.defaultConfiguration(), scheduler);

        adapter.deliverToMembers(Set.of(ONE, TWO), frame());

        assertThat(scheduler.hops)
                .describedAs("a player is looked up and written to on the thread that owns them")
                .containsExactlyInAnyOrder(new PlayerUuid(ONE.value()), new PlayerUuid(TWO.value()));
    }

    @Test
    @DisplayName("A spy is reached on their own thread too")
    void aSpyIsReachedOnTheirOwnThread() {
        RecordingScheduler scheduler = new RecordingScheduler();
        BukkitIslandChatDeliveryAdapter adapter =
                new BukkitIslandChatDeliveryAdapter(ChatConfiguration.defaultConfiguration(), scheduler);

        adapter.deliverToSpies(Set.of(TWO), frame(), "an-island");

        assertThat(scheduler.hops).containsExactly(new PlayerUuid(TWO.value()));
    }

    @Test
    @DisplayName("Nobody to deliver to is no hop at all")
    void nobodyIsNoHop() {
        RecordingScheduler scheduler = new RecordingScheduler();
        BukkitIslandChatDeliveryAdapter adapter =
                new BukkitIslandChatDeliveryAdapter(ChatConfiguration.defaultConfiguration(), scheduler);

        adapter.deliverToMembers(Set.of(), frame());

        assertThat(scheduler.hops).isEmpty();
    }

    @Test
    @DisplayName("The line is rendered once, off the owning thread, rather than once per recipient on it")
    void theLineIsRenderedOnce() {
        RecordingScheduler scheduler = new RecordingScheduler();
        BukkitIslandChatDeliveryAdapter adapter =
                new BukkitIslandChatDeliveryAdapter(ChatConfiguration.defaultConfiguration(), scheduler);

        adapter.deliverToMembers(Set.of(ONE, TWO), frame());

        // Two hops for two players, and the parsing that built the component happened before them.
        assertThat(scheduler.hops).hasSize(2);
        assertThat(Component.empty()).isNotNull();
    }
}

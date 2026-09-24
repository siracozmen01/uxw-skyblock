package com.uxplima.uxmskyblock.bukkit.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.inventory.ProfileInventoryCheckpointPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A checkpoint the database refused loses nothing: the next one writes everything since the last
 * write that went through.
 *
 * <p>The testing standard names this test. The first checkpoint fails the way a lost connection
 * fails; the session keeps the version it last wrote, and the next checkpoint writes the player's
 * whole state as it is by then, the change from before the failure included.
 */
class FailedAmbientCheckpointRetainsDirtyStateTest extends MockBukkitHarness {

    private final AtomicInteger failuresLeft = new AtomicInteger(1);
    private final AtomicInteger attempts = new AtomicInteger();
    private SessionBench bench;

    @BeforeEach
    void setUpBench() throws Exception {
        bench = new SessionBench(server, real -> failingFirst(real));
    }

    @AfterEach
    void tearDownBench() {
        bench.close();
    }

    @Test
    @DisplayName("After a failed checkpoint the next one writes what changed before and after it")
    void theNextCheckpointWritesEverything() {
        PlayerMock player = bench.inPlay(createPlayer("Dirty"));
        PlayerUuid uuid = new PlayerUuid(player.getUniqueId());
        ActiveSession session = Objects.requireNonNull(bench.coordinator.getActiveSession(player.getUniqueId()));
        long versionAtJoin = session.lastDurableVersion();

        player.getInventory().setItem(0, new ItemStack(Material.EMERALD, 2));
        bench.coordinator.checkpointPlayer(uuid);
        bench.until(() -> assertThat(attempts).hasValue(1));
        assertThat(session.lastDurableVersion())
                .describedAs("the failed write moved nothing")
                .isEqualTo(versionAtJoin);
        assertThat(SessionBench.items(bench.stored(player))).isEmpty();

        player.getInventory().setItem(1, new ItemStack(Material.QUARTZ, 5));
        bench.coordinator.checkpointPlayer(uuid);

        bench.until(() -> assertThat(session.lastDurableVersion()).isEqualTo(versionAtJoin + 1));
        assertThat(SessionBench.items(bench.stored(player)))
                .containsExactly(new ItemStack(Material.EMERALD, 2), new ItemStack(Material.QUARTZ, 5));
        assertThat(bench.coordinator.inPlay(player.getUniqueId()))
                .describedAs("a failed checkpoint does not end the session")
                .isTrue();
    }

    /** {@code real}, whose first checkpoint fails the way a dropped connection does. */
    private ProfileInventoryCheckpointPort failingFirst(ProfileInventoryCheckpointPort real) {
        return (ProfileInventoryCheckpointPort) Proxy.newProxyInstance(
                ProfileInventoryCheckpointPort.class.getClassLoader(),
                new Class<?>[] {ProfileInventoryCheckpointPort.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("checkpointInventory")) {
                        attempts.incrementAndGet();
                        if (failuresLeft.getAndDecrement() > 0) {
                            throw new IllegalStateException("the connection to the database was lost");
                        }
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}

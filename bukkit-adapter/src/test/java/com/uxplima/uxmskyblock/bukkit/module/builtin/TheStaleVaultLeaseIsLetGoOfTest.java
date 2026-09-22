package com.uxplima.uxmskyblock.bukkit.module.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.bukkit.module.BukkitModuleContext;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Nobody but this sweep closes a vault lease its holder walked away from.
 *
 * <p>A page is held one editor at a time. A player who crashes with the window open leaves the
 * session ACTIVE and the page pointing at it, and the query written to find those had no caller.
 * The sweep is the caller, so what this test guards is that the module actually starts it, on the
 * operator's interval, and stops it when the module goes down.
 */
class TheStaleVaultLeaseIsLetGoOfTest {

    @Test
    @DisplayName("Enabling the module starts a repeating sweep on the operator's interval")
    void enablingStartsTheSweep() {
        RecordingScheduler scheduler = new RecordingScheduler();
        IslandVaultService vaultService = mock(IslandVaultService.class);
        when(vaultService.closeExpiredSessions()).thenReturn(2);
        VaultConfiguration config = withSweepInterval(Duration.ofMinutes(3));

        VaultFeatureModule module = new VaultFeatureModule(vaultService, config, scheduler);
        module.enable(mock(BukkitModuleContext.class));

        assertThat(scheduler.periods).describedAs("one repeating sweep").containsExactly(Duration.ofMinutes(3));
        assertThat(scheduler.initialDelays).containsExactly(Duration.ofMinutes(3));

        scheduler.runEverything();
        assertThat(scheduler.ran)
                .describedAs("the sweep ran when its turn came")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Disabling the module stops the sweep")
    void disablingStopsTheSweep() {
        RecordingScheduler scheduler = new RecordingScheduler();
        VaultFeatureModule module = new VaultFeatureModule(
                mock(IslandVaultService.class), withSweepInterval(Duration.ofMinutes(5)), scheduler);
        module.enable(mock(BukkitModuleContext.class));

        module.disable();

        assertThat(scheduler.cancelled)
                .describedAs("the repeating task was closed")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("A sweep that throws does not take the repeating task down")
    void aFailingSweepIsSurvived() {
        IslandVaultService vaultService = mock(IslandVaultService.class);
        when(vaultService.closeExpiredSessions()).thenThrow(new IllegalStateException("the database is gone"));

        VaultFeatureModule module =
                new VaultFeatureModule(vaultService, VaultConfiguration.defaultConfiguration(), null);

        assertThat(module.closeExpiredSessions())
                .describedAs("nothing closed, and no exception out")
                .isZero();
    }

    @Test
    @DisplayName("A node with no scheduler still serves the vault")
    void noSchedulerIsNotAFailure() {
        VaultFeatureModule module =
                new VaultFeatureModule(mock(IslandVaultService.class), VaultConfiguration.defaultConfiguration());
        BukkitModuleContext context = mock(BukkitModuleContext.class);

        module.enable(context);
        module.disable();

        assertThat(module.state()).isEqualTo(com.uxplima.uxmskyblock.core.domain.module.ModuleState.DISABLED);
    }

    @Test
    @DisplayName("A sweep interval of nothing is refused rather than accepted")
    void aZeroIntervalIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> withSweepInterval(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static VaultConfiguration withSweepInterval(Duration interval) {
        VaultConfiguration defaults = VaultConfiguration.defaultConfiguration();
        return new VaultConfiguration(
                defaults.enabled(),
                defaults.basePages(),
                defaults.maxPages(),
                defaults.slotsPerPage(),
                defaults.leaseDuration(),
                defaults.auditLogLimit(),
                interval);
    }

    /** Holds on to what was scheduled instead of running it, so the test decides when it runs. */
    private static final class RecordingScheduler implements SchedulerPort {

        final List<Duration> initialDelays = new ArrayList<>();
        final List<Duration> periods = new ArrayList<>();
        private final List<Runnable> tasks = new ArrayList<>();
        int ran;
        int cancelled;

        void runEverything() {
            for (Runnable task : tasks) {
                task.run();
                ran++;
            }
        }

        @Override
        public void onGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void laterGlobal(Duration delay, Runnable task) {
            task.run();
        }

        @Override
        public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {
            task.run();
        }

        @Override
        public void onEntity(PlayerUuid playerUuid, Runnable task) {
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
        public AutoCloseable repeatAsync(Runnable task, Duration initialDelay, Duration period) {
            initialDelays.add(initialDelay);
            periods.add(period);
            tasks.add(task);
            return () -> cancelled++;
        }

        @Override
        public AutoCloseable repeatGlobal(Runnable task, Duration initialDelay, Duration period) {
            return repeatAsync(task, initialDelay, period);
        }
    }
}

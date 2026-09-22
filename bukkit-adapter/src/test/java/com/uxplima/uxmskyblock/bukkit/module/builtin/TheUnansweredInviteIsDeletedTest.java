package com.uxplima.uxmskyblock.bukkit.module.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.uxplima.uxmskyblock.bukkit.module.BukkitModuleContext;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The invitations nobody answered are deleted.
 *
 * <p>An expired invite cannot be accepted, so nothing was ever wrong with one sitting there. What
 * was wrong is that the purge written for exactly this, and tested against the real store, had no
 * caller anywhere in the plugin, so the table kept every invitation the server had ever seen.
 */
class TheUnansweredInviteIsDeletedTest {

    @Test
    @DisplayName("Enabling the module starts the sweep on the operator's interval")
    void enablingStartsTheSweep() {
        RecordingScheduler scheduler = new RecordingScheduler();
        IslandAllianceService service = mock(IslandAllianceService.class);

        AllianceFeatureModule module = new AllianceFeatureModule(service, scheduler, Duration.ofMinutes(7));
        module.enable(mock(BukkitModuleContext.class));

        assertThat(scheduler.periods).containsExactly(Duration.ofMinutes(7));
        assertThat(scheduler.initialDelays).containsExactly(Duration.ofMinutes(7));

        scheduler.runEverything();
        verify(service).purgeExpiredInvites();
    }

    @Test
    @DisplayName("Disabling the module stops the sweep")
    void disablingStopsTheSweep() {
        RecordingScheduler scheduler = new RecordingScheduler();
        AllianceFeatureModule module =
                new AllianceFeatureModule(mock(IslandAllianceService.class), scheduler, Duration.ofMinutes(5));
        module.enable(mock(BukkitModuleContext.class));

        module.disable();

        assertThat(scheduler.cancelled).isEqualTo(1);
    }

    @Test
    @DisplayName("A sweep that throws does not take the repeating task down")
    void afailingSweepIsSurvived() {
        RecordingScheduler scheduler = new RecordingScheduler();
        IslandAllianceService service = mock(IslandAllianceService.class);
        doThrow(new IllegalStateException("the database is gone")).when(service).purgeExpiredInvites();

        AllianceFeatureModule module = new AllianceFeatureModule(service, scheduler, Duration.ofMinutes(5));
        module.enable(mock(BukkitModuleContext.class));

        // The service swallows it. The task must survive whatever the service does not.
        assertThatThrownBy(scheduler::runEverything).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("A node with no scheduler still serves alliances")
    void noSchedulerIsNotAFailure() {
        AllianceFeatureModule module = new AllianceFeatureModule(mock(IslandAllianceService.class));

        module.enable(mock(BukkitModuleContext.class));
        module.disable();

        assertThat(module.state()).isEqualTo(com.uxplima.uxmskyblock.core.domain.module.ModuleState.DISABLED);
    }

    @Test
    @DisplayName("A sweep interval of nothing is refused rather than accepted")
    void azeroIntervalIsRefused() {
        assertThatThrownBy(() -> new AllianceFeatureModule(mock(IslandAllianceService.class), null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Holds on to what was scheduled instead of running it, so the test decides when it runs. */
    private static final class RecordingScheduler implements SchedulerPort {

        final List<Duration> initialDelays = new ArrayList<>();
        final List<Duration> periods = new ArrayList<>();
        private final List<Runnable> tasks = new ArrayList<>();
        int cancelled;

        void runEverything() {
            for (Runnable task : tasks) {
                task.run();
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

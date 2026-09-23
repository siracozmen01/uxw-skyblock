package com.uxplima.uxmskyblock.bukkit.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/**
 * A shutdown waits for a repeating task's run that is under way.
 *
 * <p>The drain counted one-off async work and not the runs of a repeating task, so the pool was closed
 * under a season check, a sweep or a heartbeat that had started, and it failed with the pool closed.
 * The server's async scheduler is stood in for, because the run it hands out is what is counted.
 */
class ARepeatingRunIsDrainedTest {

    @Test
    @DisplayName("A drain waits for a repeating run that has started, and finishes once it has")
    @SuppressWarnings("unchecked")
    void aRunningRepeatIsWaitedFor() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        AsyncScheduler async = mock(AsyncScheduler.class);
        ArgumentCaptor<Consumer<ScheduledTask>> run = ArgumentCaptor.forClass(Consumer.class);
        when(async.runAtFixedRate(eq(plugin), run.capture(), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(mock(ScheduledTask.class));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getAsyncScheduler).thenReturn(async);
            FoliaSchedulerAdapter scheduler = new FoliaSchedulerAdapter(plugin);
            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            scheduler.repeatAsync(
                    () -> {
                        running.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    },
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(60));

            Thread server = new Thread(() -> run.getValue().accept(mock(ScheduledTask.class)));
            server.start();
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(scheduler.drainAsync(Duration.ofMillis(100)))
                    .describedAs("the run is still under way")
                    .isFalse();
            release.countDown();
            assertThat(scheduler.drainAsync(Duration.ofSeconds(5))).isTrue();
            server.join();
        }
    }
}

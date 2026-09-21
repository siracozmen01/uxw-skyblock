package com.uxplima.uxmskyblock.bukkit.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.plugin.Plugin;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FoliaSchedulerAdapterTest {

    @Test
    @DisplayName("onGlobal is a silent no-op when plugin is disabled")
    void onGlobalIsNoOpWhenPluginIsDisabled() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(false);
        FoliaSchedulerAdapter scheduler = new FoliaSchedulerAdapter(plugin);
        AtomicBoolean ran = new AtomicBoolean(false);

        scheduler.onGlobal(() -> ran.set(true));

        assertThat(ran).isFalse();
    }

    @Test
    @DisplayName("onRegion is a silent no-op when plugin is disabled")
    void onRegionIsNoOpWhenPluginIsDisabled() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(false);
        FoliaSchedulerAdapter scheduler = new FoliaSchedulerAdapter(plugin);
        AtomicBoolean ran = new AtomicBoolean(false);

        scheduler.onRegion("test_world", 0, 0, () -> ran.set(true));

        assertThat(ran).isFalse();
    }

    @Test
    @DisplayName("onEntity is a silent no-op when plugin is disabled")
    void onEntityIsNoOpWhenPluginIsDisabled() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(false);
        FoliaSchedulerAdapter scheduler = new FoliaSchedulerAdapter(plugin);
        AtomicBoolean ran = new AtomicBoolean(false);
        AtomicBoolean retired = new AtomicBoolean(false);

        scheduler.onEntity(PlayerUuid.of(UUID.randomUUID()), () -> ran.set(true), () -> retired.set(true));

        assertThat(ran).isFalse();
        assertThat(retired).isFalse();
    }

    @Test
    @DisplayName("async methods do not throw or run if plugin is disabled")
    void asyncIsNoOpWhenPluginIsDisabled() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(false);
        FoliaSchedulerAdapter scheduler = new FoliaSchedulerAdapter(plugin);
        AtomicBoolean ran = new AtomicBoolean(false);

        scheduler.async(() -> ran.set(true));
        scheduler.asyncAfter(Duration.ofMillis(100), () -> ran.set(true));

        assertThat(ran).isFalse();
    }

    @Test
    @DisplayName("A drain with nothing running returns at once")
    void drainReturnsWhenNothingIsRunning() {
        Plugin plugin = mock(Plugin.class);
        FoliaSchedulerAdapter scheduler = new FoliaSchedulerAdapter(plugin);

        assertThat(scheduler.drainAsync(Duration.ofSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("A drain waits for work that is still running, then reports that it finished")
    void drainWaitsForRunningWork() throws Exception {
        Plugin plugin = mock(Plugin.class);
        FoliaSchedulerAdapter scheduler = new FoliaSchedulerAdapter(plugin);
        scheduler.beginAsync();
        CountDownLatch started = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            started.countDown();
            try {
                Thread.sleep(120L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler.endAsync();
        });
        worker.start();
        started.await();

        assertThat(scheduler.drainAsync(Duration.ofSeconds(5))).isTrue();
        worker.join();
    }

    @Test
    @DisplayName("A drain that runs out of time says so, so the shutdown can log the loss")
    void drainReportsTimeout() {
        Plugin plugin = mock(Plugin.class);
        FoliaSchedulerAdapter scheduler = new FoliaSchedulerAdapter(plugin);
        scheduler.beginAsync();

        assertThat(scheduler.drainAsync(Duration.ofMillis(50))).isFalse();

        scheduler.endAsync();
    }

    @Test
    @DisplayName("Work that never runs because the plugin is disabled is not work a drain waits for")
    void disabledWorkIsNotCounted() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(false);
        FoliaSchedulerAdapter scheduler = new FoliaSchedulerAdapter(plugin);

        scheduler.async(() -> {});
        scheduler.asyncAfter(Duration.ofMillis(10), () -> {});

        assertThat(scheduler.drainAsync(Duration.ofMillis(50))).isTrue();
    }
}

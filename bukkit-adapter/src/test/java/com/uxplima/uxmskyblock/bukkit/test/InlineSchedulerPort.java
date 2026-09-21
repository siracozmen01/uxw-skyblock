package com.uxplima.uxmskyblock.bukkit.test;

import java.time.Duration;

import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * A scheduler that runs everything where it stands.
 *
 * <p>For a test that wants to see what a hop would have done without a server behind it. Every
 * other test in this module wrote its own; this one is shared so a new one does not have to.
 */
public final class InlineSchedulerPort implements SchedulerPort {

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
        return () -> {};
    }

    @Override
    public AutoCloseable repeatGlobal(Runnable task, Duration initialDelay, Duration period) {
        return () -> {};
    }
}

package com.uxplima.uxmskyblock.bukkit.scheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * Folia-aware implementation of {@link SchedulerPort} delegating to Paper/Folia region schedulers.
 *
 * <p>Dispatches tasks to the appropriate execution context:
 * <ul>
 *   <li>{@link #onGlobal}: {@link org.bukkit.Bukkit#getGlobalRegionScheduler()}</li>
 *   <li>{@link #onRegion}: {@link org.bukkit.Bukkit#getRegionScheduler()}</li>
 *   <li>{@link #onEntity}: {@link org.bukkit.entity.Entity#getScheduler()}</li>
 *   <li>{@link #async}, {@link #asyncAfter}, {@link #repeatAsync}: {@link org.bukkit.Bukkit#getAsyncScheduler()}</li>
 * </ul>
 */
public final class FoliaSchedulerAdapter implements SchedulerPort {

    private final Plugin plugin;

    public FoliaSchedulerAdapter(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    private boolean disabled() {
        return !plugin.isEnabled();
    }

    @Override
    public void onGlobal(Runnable task) {
        Objects.requireNonNull(task, "task must not be null");
        if (disabled()) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }

    @Override
    public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {
        Objects.requireNonNull(worldName, "worldName must not be null");
        Objects.requireNonNull(task, "task must not be null");
        if (disabled()) {
            return;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return;
        }
        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, task);
    }

    @Override
    public void onEntity(PlayerUuid playerUuid, Runnable task) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(task, "task must not be null");
        if (disabled()) {
            return;
        }
        Player player = Bukkit.getPlayer(playerUuid.value());
        if (player == null || !player.isOnline()) {
            return;
        }
        player.getScheduler().execute(plugin, task, null, 1L);
    }

    @Override
    public void onEntity(PlayerUuid playerUuid, Runnable task, Runnable retired) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(retired, "retired must not be null");
        if (disabled()) {
            return;
        }
        Player player = Bukkit.getPlayer(playerUuid.value());
        if (player == null || !player.isOnline()) {
            retired.run();
            return;
        }
        player.getScheduler().execute(plugin, task, retired, 1L);
    }

    @Override
    public boolean onGlobalThread() {
        return Bukkit.isGlobalTickThread();
    }

    @Override
    public boolean ownsEntity(PlayerUuid playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Player player = Bukkit.getPlayer(playerUuid.value());
        return player != null && player.isOnline() && Bukkit.isOwnedByCurrentRegion(player);
    }

    @Override
    public void async(Runnable task) {
        Objects.requireNonNull(task, "task must not be null");
        if (disabled()) {
            return;
        }
        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> task.run());
    }

    @Override
    public void asyncAfter(Duration delay, Runnable task) {
        Objects.requireNonNull(delay, "delay must not be null");
        Objects.requireNonNull(task, "task must not be null");
        if (disabled()) {
            return;
        }
        long millis = Math.max(0L, delay.toMillis());
        Bukkit.getAsyncScheduler()
                .runDelayed(plugin, ignored -> task.run(), Math.max(1L, millis), TimeUnit.MILLISECONDS);
    }

    @Override
    public void laterGlobal(Duration delay, Runnable task) {
        Objects.requireNonNull(delay, "delay must not be null");
        Objects.requireNonNull(task, "task must not be null");
        if (disabled()) {
            return;
        }
        long ticks = Math.max(1L, delay.toMillis() / 50L);
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignored -> task.run(), ticks);
    }

    @Override
    public AutoCloseable repeatGlobal(Runnable task, Duration initialDelay, Duration period) {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(initialDelay, "initialDelay must not be null");
        Objects.requireNonNull(period, "period must not be null");
        if (disabled()) {
            return () -> {};
        }
        long initTicks = Math.max(1L, initialDelay.toMillis() / 50L);
        long periodTicks = Math.max(1L, period.toMillis() / 50L);
        ScheduledTask scheduledTask =
                Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> task.run(), initTicks, periodTicks);
        return scheduledTask::cancel;
    }

    @Override
    public AutoCloseable repeatAsync(Runnable task, Duration initialDelay, Duration period) {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(initialDelay, "initialDelay must not be null");
        Objects.requireNonNull(period, "period must not be null");
        if (disabled()) {
            return () -> {};
        }
        long initMillis = Math.max(1L, initialDelay.toMillis());
        long periodMillis = Math.max(1L, period.toMillis());
        ScheduledTask scheduledTask = Bukkit.getAsyncScheduler()
                .runAtFixedRate(plugin, ignored -> task.run(), initMillis, periodMillis, TimeUnit.MILLISECONDS);
        return scheduledTask::cancel;
    }
}

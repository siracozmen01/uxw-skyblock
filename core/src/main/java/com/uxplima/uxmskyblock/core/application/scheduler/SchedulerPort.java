package com.uxplima.uxmskyblock.core.application.scheduler;

import java.time.Duration;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * Folia-aware scheduling port for Skyblock application services and adapters.
 *
 * <p>Dispatches work onto the appropriate Folia scheduler ({@code GlobalRegionScheduler},
 * {@code RegionScheduler}, {@code EntityScheduler}, or {@code AsyncScheduler}).
 * Legacy Bukkit schedulers ({@code BukkitScheduler} and {@code BukkitRunnable}) are forbidden.
 */
public interface SchedulerPort {

    /**
     * Run on the global region thread. Global game state only; serialises execution, so use sparingly.
     *
     * @param task the runnable to execute
     */
    void onGlobal(Runnable task);

    /**
     * Run on the region thread owning the specified chunk in the given world.
     *
     * @param worldName the name of the world
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @param task the runnable to execute
     */
    void onRegion(String worldName, int chunkX, int chunkZ, Runnable task);

    /**
     * Run on the region thread owning the player's entity. Silently no-ops if the player is offline.
     *
     * @param playerUuid player identifier
     * @param task the runnable to execute
     */
    void onEntity(PlayerUuid playerUuid, Runnable task);

    /**
     * Convenience overload for {@link #onEntity(PlayerUuid, Runnable)}.
     */
    default void onEntity(UUID playerUuid, Runnable task) {
        onEntity(PlayerUuid.of(playerUuid), task);
    }

    /**
     * Run on the region thread owning the player's entity, invoking {@code retired} if the player
     * is offline or despawned before the task executes.
     *
     * @param playerUuid player identifier
     * @param task the runnable to execute
     * @param retired callback invoked if the player entity is retired or offline
     */
    default void onEntity(PlayerUuid playerUuid, Runnable task, Runnable retired) {
        onEntity(playerUuid, task);
    }

    /**
     * Convenience overload for {@link #onEntity(PlayerUuid, Runnable, Runnable)}.
     */
    default void onEntity(UUID playerUuid, Runnable task, Runnable retired) {
        onEntity(PlayerUuid.of(playerUuid), task, retired);
    }

    /**
     * Whether the calling thread already owns the global region.
     *
     * @return true if currently executing on the global region thread
     */
    default boolean onGlobalThread() {
        return false;
    }

    /**
     * Whether the calling thread already owns the entity's region.
     *
     * @param playerUuid player identifier
     * @return true if currently executing on the player's region thread
     */
    default boolean ownsEntity(PlayerUuid playerUuid) {
        return false;
    }

    /**
     * Convenience overload for {@link #ownsEntity(PlayerUuid)}.
     */
    default boolean ownsEntity(UUID playerUuid) {
        return ownsEntity(PlayerUuid.of(playerUuid));
    }

    /**
     * Run off any tick thread asynchronously on the worker pool. Must never touch the Bukkit API.
     *
     * @param task the runnable to execute
     */
    void async(Runnable task);

    /**
     * Run off any tick thread asynchronously after a specified delay.
     *
     * @param delay the duration to wait
     * @param task the runnable to execute
     */
    void asyncAfter(Duration delay, Runnable task);

    /**
     * Run task once on the global region thread after a specified delay.
     *
     * @param delay the duration to wait
     * @param task the runnable to execute
     */
    void laterGlobal(Duration delay, Runnable task);

    /**
     * Schedule a task to run repeatedly on the global region thread.
     *
     * @param task the runnable to execute
     * @param initialDelay initial delay before first execution
     * @param period delay between subsequent executions
     * @return an {@link AutoCloseable} that cancels the repeating execution
     */
    AutoCloseable repeatGlobal(Runnable task, Duration initialDelay, Duration period);

    /**
     * Schedule a task to run repeatedly asynchronously on the worker pool.
     *
     * @param task the runnable to execute
     * @param initialDelay initial delay before first execution
     * @param period delay between subsequent executions
     * @return an {@link AutoCloseable} that cancels the repeating execution
     */
    AutoCloseable repeatAsync(Runnable task, Duration initialDelay, Duration period);
}

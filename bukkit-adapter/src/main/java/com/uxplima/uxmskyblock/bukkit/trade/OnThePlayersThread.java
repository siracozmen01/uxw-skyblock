package com.uxplima.uxmskyblock.bukkit.trade;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;

/**
 * Runs work on the thread that owns a player and waits for it, from a thread that does not.
 *
 * <p>On Folia a player's inventory belongs to their region and on Paper to the main thread; a trade is
 * carried out on an asynchronous thread, because it waits on the database. The work runs at most once
 * and never after the wait gave up: an inventory changed after the trade reported it unchanged would
 * be changed and not journaled. When the player leaves first, or their thread does not get to it in
 * time, {@code gone} is the answer and nothing was done.
 */
public final class OnThePlayersThread {

    private static final long WAIT_SECONDS = 10;

    private final SchedulerPort scheduler;

    public OnThePlayersThread(SchedulerPort scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    <T> T run(UUID player, Supplier<T> work, T gone) {
        if (scheduler.ownsEntity(player)) {
            return work.get();
        }
        AtomicBoolean claimed = new AtomicBoolean();
        CompletableFuture<T> done = new CompletableFuture<>();
        scheduler.onEntity(
                player,
                () -> {
                    if (!claimed.compareAndSet(false, true)) {
                        return;
                    }
                    try {
                        done.complete(work.get());
                    } catch (RuntimeException e) {
                        done.completeExceptionally(e);
                    }
                },
                () -> {
                    if (claimed.compareAndSet(false, true)) {
                        done.complete(gone);
                    }
                });
        try {
            return done.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException slow) {
            if (claimed.compareAndSet(false, true)) {
                return gone;
            }
            // It started just now, so it finishes: wait for what it did.
            return done.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (claimed.compareAndSet(false, true)) {
                return gone;
            }
            return done.join();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException runtime ? runtime : new IllegalStateException(cause);
        }
    }
}

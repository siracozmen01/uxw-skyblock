package com.uxplima.uxmskyblock.bukkit.snapshot;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * How long a capture waits for its regions, and what it does when one never answers.
 *
 * <p>A capture asks each owning region for its chunks and waited for all of them with no limit. A
 * region that never ran the task, one whose chunks had unloaded or whose thread was stuck, held the
 * backup open for ever, and the command that asked for it never answered.
 */
final class CaptureDeadline {

    private CaptureDeadline() {}

    /** {@code timeout}, refused unless it is positive. */
    static Duration checked(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("A capture timeout must be positive: " + timeout);
        }
        return timeout;
    }

    /**
     * Waits for every region's chunks, or gives the capture up whole once {@code timeout} has passed.
     *
     * <p>Nothing is written for a capture given up: the archive stream is thrown away with the error,
     * and a region that answers afterwards adds its chunks to a list nobody reads again.
     */
    static void await(List<CompletableFuture<Void>> regions, Duration timeout) {
        try {
            CompletableFuture.allOf(regions.toArray(CompletableFuture<?>[]::new))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            throw new IllegalStateException(
                    "A region did not hand its chunks over within " + timeout + ", so nothing was captured", late);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The capture was interrupted, so nothing was captured", interrupted);
        } catch (ExecutionException failed) {
            throw new IllegalStateException("A region failed its part of the capture", failed.getCause());
        }
    }
}

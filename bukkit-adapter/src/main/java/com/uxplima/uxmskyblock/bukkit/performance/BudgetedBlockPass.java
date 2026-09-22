package com.uxplima.uxmskyblock.bukkit.performance;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;

import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;

/**
 * Walks every block of one chunk's column, a tick's worth at a time.
 *
 * <p>Clearing an island and putting one back both walk the same shape: every block between two
 * corners, floor to ceiling, inside one chunk. Both did it in a single region task, so one chunk
 * column was tens of thousands of block writes landing on one tick, and the region that owned it
 * did nothing else meanwhile. The operator's file has named a budget for this since the performance
 * work and nothing read it.
 *
 * <p>The budget is spent on writes, not on positions looked at. Looking at a block that is already
 * what it should be costs a lookup; writing one costs a block update, the light that follows it and
 * every neighbour that has to be told. The budget is read again for every slice, so a server that
 * falls behind halfway through finishes the rest slower.
 *
 * <p>Between slices the walk hands the region back through the scheduler. On Folia that is the next
 * tick of that region. A scheduler that runs the next slice in place instead, which is what a test
 * does, finds this looping rather than nesting, so neither the stack nor the region is held.
 */
public final class BudgetedBlockPass {

    /** What to do at one position. Returns true when it wrote something, which is what costs. */
    @FunctionalInterface
    public interface BlockWork {
        boolean apply(int x, int y, int z);
    }

    private final SchedulerPort scheduler;
    private final String worldName;
    private final int chunkX;
    private final int chunkZ;
    private final int endX;
    private final int startZ;
    private final int endZ;
    private final int minY;
    private final int maxY;
    private final IntSupplier budget;
    private final BlockWork work;
    private final CompletableFuture<Void> done = new CompletableFuture<>();

    private int x;
    private int z;
    private int y;
    private boolean inside;
    private boolean again;

    private BudgetedBlockPass(
            SchedulerPort scheduler,
            String worldName,
            int chunkX,
            int chunkZ,
            int startX,
            int endX,
            int startZ,
            int endZ,
            int minY,
            int maxY,
            IntSupplier budget,
            BlockWork work) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.worldName = Objects.requireNonNull(worldName, "worldName must not be null");
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.endX = endX;
        this.startZ = startZ;
        this.endZ = endZ;
        this.minY = minY;
        this.maxY = maxY;
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
        this.work = Objects.requireNonNull(work, "work must not be null");
        this.x = startX;
        this.z = startZ;
        this.y = minY;
    }

    /**
     * Starts the walk on the thread that owns this chunk and finishes when the last block is done.
     *
     * <p>The caller is expected to be on that thread already, which is where both callers are: this
     * is the body of their region task, not something to hand to another one.
     */
    public static CompletableFuture<Void> over(
            SchedulerPort scheduler,
            String worldName,
            int chunkX,
            int chunkZ,
            int startX,
            int endX,
            int startZ,
            int endZ,
            int minY,
            int maxY,
            IntSupplier budget,
            BlockWork work) {
        BudgetedBlockPass pass = new BudgetedBlockPass(
                scheduler, worldName, chunkX, chunkZ, startX, endX, startZ, endZ, minY, maxY, budget, work);
        pass.run();
        return pass.done;
    }

    private void run() {
        if (inside) {
            again = true;
            return;
        }
        inside = true;
        try {
            do {
                again = false;
                slice();
            } while (again);
        } finally {
            inside = false;
        }
    }

    private void slice() {
        int allowed = Math.max(1, budget.getAsInt());
        int written = 0;
        try {
            while (x <= endX) {
                while (z <= endZ) {
                    while (y < maxY) {
                        if (work.apply(x, y, z)) {
                            written++;
                        }
                        y++;
                        if (written >= allowed) {
                            scheduler.onRegion(worldName, chunkX, chunkZ, this::run);
                            return;
                        }
                    }
                    y = minY;
                    z++;
                }
                z = startZ;
                x++;
            }
        } catch (Throwable t) {
            done.completeExceptionally(t);
            return;
        }
        done.complete(null);
    }
}

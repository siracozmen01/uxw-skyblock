package com.uxplima.uxmskyblock.core.application.oneblock;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;
import org.jspecify.annotations.Nullable;

/**
 * What happens when a OneBlock island's block is broken, and keeping count of it.
 *
 * <p>A block breaks every few ticks on a busy island, and a row written for every one of them would
 * be the busiest table on the server. The count is kept in memory, each island's own, and what has
 * been counted since the last write is added to the stored count on a schedule and when the server
 * stops. A crash loses at most the breaks of one interval, and those only move a phase a little later;
 * no item or balance hangs on the count.
 */
public final class OneBlockService {

    private static final Logger LOGGER = Logger.getLogger(OneBlockService.class.getName());

    /** What one break did: what the block becomes, what appears on it, and whether a phase began. */
    public record Broken(
            String nextBlock, Optional<String> creature, OneBlockPhases.Position position, boolean phaseBegan) {}

    /** An island's count while it is in memory: what is stored, and what was counted since. */
    private static final class Counted {
        final OneBlockProgressPort.OneBlockIsland stored;
        final AtomicLong total;
        final AtomicLong unwritten = new AtomicLong();

        Counted(OneBlockProgressPort.OneBlockIsland stored) {
            this.stored = stored;
            this.total = new AtomicLong(stored.blocksBroken());
        }
    }

    private final OneBlockProgressPort progress;
    private final OneBlockPhases phases;
    private final RandomGenerator random;
    private final Map<IslandId, Counted> counted = new ConcurrentHashMap<>();

    /** Islands read once and found not to be OneBlock islands, so a block broken on one asks nothing. */
    private final java.util.Set<IslandId> notOneBlock = ConcurrentHashMap.newKeySet();

    public OneBlockService(OneBlockProgressPort progress, OneBlockPhases phases, RandomGenerator random) {
        this.progress = Objects.requireNonNull(progress, "progress must not be null");
        this.phases = Objects.requireNonNull(phases, "phases must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    /** The island as a OneBlock island, read once and then kept, or nothing if it is not one. */
    public Optional<OneBlockProgressPort.OneBlockIsland> island(IslandId islandId) {
        Counted held = held(islandId);
        if (held == null) {
            return Optional.empty();
        }
        OneBlockProgressPort.OneBlockIsland stored = held.stored;
        return Optional.of(new OneBlockProgressPort.OneBlockIsland(
                islandId, stored.x(), stored.y(), stored.z(), held.total.get()));
    }

    /** Where the island stands in its phases now, if it is a OneBlock island. */
    public Optional<OneBlockPhases.Position> position(IslandId islandId) {
        Counted held = held(islandId);
        return held == null ? Optional.empty() : Optional.of(phases.positionAt(held.total.get()));
    }

    /** Counts one break of the island's block and says what it turns into, or nothing if it is not one. */
    public Optional<Broken> onBreak(IslandId islandId) {
        Counted held = held(islandId);
        if (held == null) {
            return Optional.empty();
        }
        long broken = held.total.incrementAndGet();
        held.unwritten.incrementAndGet();
        return Optional.of(new Broken(
                phases.nextBlock(broken, random),
                phases.nextCreature(broken, random),
                phases.positionAt(broken),
                phases.startsAPhase(broken)));
    }

    /** Makes a new island a OneBlock island whose block stands at x, y, z. */
    public void start(IslandId islandId, int x, int y, int z) {
        progress.start(islandId, x, y, z);
        counted.remove(islandId);
        notOneBlock.remove(islandId);
    }

    /**
     * Adds every island's unwritten breaks to its stored count. An island whose write fails keeps its
     * breaks for the next time; the others are written regardless.
     *
     * @return how many islands were written
     */
    public int flush() {
        int written = 0;
        for (Map.Entry<IslandId, Counted> entry : counted.entrySet()) {
            long pending = entry.getValue().unwritten.getAndSet(0);
            if (pending == 0) {
                continue;
            }
            try {
                progress.addBreaks(entry.getKey(), pending);
                written++;
            } catch (RuntimeException failed) {
                entry.getValue().unwritten.addAndGet(pending);
                LOGGER.log(
                        Level.WARNING,
                        failed,
                        () -> "Writing the OneBlock count of island " + entry.getKey() + " failed; it is kept.");
            }
        }
        return written;
    }

    /** Writes what the island has counted and lets go of it, as when it is deleted or leaves this node. */
    public void forgetIsland(IslandId islandId) {
        notOneBlock.remove(islandId);
        Counted held = counted.remove(islandId);
        if (held == null) {
            return;
        }
        long pending = held.unwritten.getAndSet(0);
        if (pending > 0) {
            try {
                progress.addBreaks(islandId, pending);
            } catch (RuntimeException gone) {
                LOGGER.log(Level.FINE, gone, () -> "Island " + islandId + " kept no OneBlock count to write.");
            }
        }
    }

    private @Nullable Counted held(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Counted held = counted.get(islandId);
        if (held != null) {
            return held;
        }
        if (notOneBlock.contains(islandId)) {
            return null;
        }
        Optional<OneBlockProgressPort.OneBlockIsland> stored = progress.find(islandId);
        if (stored.isEmpty()) {
            notOneBlock.add(islandId);
            return null;
        }
        return counted.computeIfAbsent(islandId, id -> new Counted(stored.get()));
    }
}

package com.uxplima.uxmskyblock.core.application.booster;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.IntSupplier;

import com.uxplima.uxmskyblock.core.application.lock.KeyedMutationLock;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterApplyResult;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCalculation;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterDurationPolicy;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterStackMode;
import com.uxplima.uxmskyblock.core.domain.booster.CategoryBoosterPolicy;
import com.uxplima.uxmskyblock.core.domain.booster.IslandBooster;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Domain application service governing island boosters, multiplier calculations,
 * category stacking modes, decay policies, and pause-on-idle semantics.
 */
public final class IslandBoosterService {

    private final IslandBoosterStoragePort storagePort;
    private final Function<BoosterCategory, CategoryBoosterPolicy> policyProvider;
    private final boolean pauseWhenEmptyEnabled;
    private final Set<IslandId> pausedIslands = ConcurrentHashMap.newKeySet();

    /**
     * Holds one island to one pause or resume at a time.
     *
     * <p>The two arrive from a join and a quit on the same island in the same second, and both used
     * to read the boosters, decide, and write with nothing held.
     */
    private final KeyedMutationLock<IslandId> boosterLock = new KeyedMutationLock<>();

    public IslandBoosterService(
            IslandBoosterStoragePort storagePort,
            Function<BoosterCategory, CategoryBoosterPolicy> policyProvider,
            boolean pauseWhenEmptyEnabled) {
        this.storagePort = Objects.requireNonNull(storagePort, "storagePort must not be null");
        this.policyProvider = Objects.requireNonNull(policyProvider, "policyProvider must not be null");
        this.pauseWhenEmptyEnabled = pauseWhenEmptyEnabled;
    }

    public CategoryBoosterPolicy policy(BoosterCategory category) {
        Objects.requireNonNull(category, "category must not be null");
        CategoryBoosterPolicy policy = policyProvider.apply(category);
        return policy != null ? policy : CategoryBoosterPolicy.defaultFor(category);
    }

    public boolean isPauseWhenEmptyEnabled() {
        return pauseWhenEmptyEnabled;
    }

    /**
     * Attempts to apply a booster to the specified island.
     *
     * @param islandId target island
     * @param category multiplier category
     * @param multiplier numerical multiplier
     * @param duration requested booster duration
     * @param now current instant
     * @return outcome of the application attempt
     */
    public BoosterApplyResult applyBooster(
            IslandId islandId, BoosterCategory category, double multiplier, Duration duration, Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        Objects.requireNonNull(now, "now must not be null");

        CategoryBoosterPolicy policy = policy(category);
        if (!policy.enabled()) {
            return new BoosterApplyResult.CategoryDisabled(category);
        }

        List<IslandBooster> active = getActiveBoosters(islandId, category, now);
        boolean isIslandPaused = isIslandPaused(islandId);

        if (active.isEmpty()) {
            Duration cappedDuration = duration.compareTo(policy.maxDuration()) > 0 ? policy.maxDuration() : duration;
            double cappedMultiplier = Math.min(policy.maxMultiplier(), multiplier);

            IslandBooster booster = IslandBooster.create(islandId, category, cappedMultiplier, cappedDuration, now);
            if (isIslandPaused && pauseWhenEmptyEnabled) {
                booster = booster.withPause(now);
            }
            storagePort.saveBooster(booster);
            return new BoosterApplyResult.Success(booster, cappedMultiplier, cappedDuration);
        }

        return switch (policy.stackMode()) {
            case DURATION -> handleDurationStacking(policy, active.getFirst(), duration, now);
            case MULTIPLIER ->
                handleMultiplierStacking(islandId, category, policy, active, multiplier, duration, isIslandPaused, now);
            case REPLACE ->
                handleReplaceStacking(islandId, category, policy, active, multiplier, duration, isIslandPaused, now);
        };
    }

    private BoosterApplyResult handleDurationStacking(
            CategoryBoosterPolicy policy, IslandBooster existing, Duration additionalDuration, Instant now) {
        IslandBooster extended = existing.withExtendedDuration(additionalDuration, now, policy.maxDuration());
        storagePort.saveBooster(extended);

        Duration effectiveRemaining = extended.effectiveRemainingDuration(now);
        boolean capped = effectiveRemaining.compareTo(policy.maxDuration()) >= 0;
        return new BoosterApplyResult.DurationExtended(extended, extended.multiplier(), effectiveRemaining, capped);
    }

    private BoosterApplyResult handleMultiplierStacking(
            IslandId islandId,
            BoosterCategory category,
            CategoryBoosterPolicy policy,
            List<IslandBooster> activeBoosters,
            double newMultiplier,
            Duration requestedDuration,
            boolean isIslandPaused,
            Instant now) {
        Duration actualDuration =
                requestedDuration.compareTo(policy.maxDuration()) > 0 ? policy.maxDuration() : requestedDuration;

        if (policy.durationPolicy() == BoosterDurationPolicy.REFRESH) {
            List<IslandBooster> refreshed = new ArrayList<>();
            for (IslandBooster existing : activeBoosters) {
                refreshed.add(existing.withRefreshedDuration(actualDuration, now));
            }
            storagePort.saveAll(refreshed);
        }

        IslandBooster newBooster = IslandBooster.create(islandId, category, newMultiplier, actualDuration, now);
        if (isIslandPaused && pauseWhenEmptyEnabled) {
            newBooster = newBooster.withPause(now);
        }
        storagePort.saveBooster(newBooster);

        double effective = getEffectiveMultiplier(islandId, category, now);
        boolean capped = effective >= policy.maxMultiplier();
        Duration remaining = newBooster.effectiveRemainingDuration(now);

        return new BoosterApplyResult.MultiplierStacked(newBooster, effective, remaining, capped);
    }

    private BoosterApplyResult handleReplaceStacking(
            IslandId islandId,
            BoosterCategory category,
            CategoryBoosterPolicy policy,
            List<IslandBooster> activeBoosters,
            double newMultiplier,
            Duration requestedDuration,
            boolean isIslandPaused,
            Instant now) {
        double currentMax = activeBoosters.stream()
                .mapToDouble(IslandBooster::multiplier)
                .max()
                .orElse(1.0);

        if (newMultiplier <= currentMax) {
            return new BoosterApplyResult.RejectedLowerTier(currentMax, newMultiplier);
        }

        for (IslandBooster old : activeBoosters) {
            storagePort.deleteById(old.id());
        }

        Duration actualDuration =
                requestedDuration.compareTo(policy.maxDuration()) > 0 ? policy.maxDuration() : requestedDuration;
        double cappedMultiplier = Math.min(policy.maxMultiplier(), newMultiplier);

        IslandBooster newBooster = IslandBooster.create(islandId, category, cappedMultiplier, actualDuration, now);
        if (isIslandPaused && pauseWhenEmptyEnabled) {
            newBooster = newBooster.withPause(now);
        }
        storagePort.saveBooster(newBooster);

        return new BoosterApplyResult.Replaced(activeBoosters.getFirst(), newBooster);
    }

    /**
     * Calculates the effective multiplier for the specified island and category at the given instant.
     * Returns 1.0 (base) if no active boosters are present.
     */
    public double getEffectiveMultiplier(IslandId islandId, BoosterCategory category, Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(now, "now must not be null");

        CategoryBoosterPolicy policy = policy(category);
        if (!policy.enabled()) {
            return 1.0;
        }

        List<IslandBooster> active = getActiveBoosters(islandId, category, now);
        if (active.isEmpty()) {
            return 1.0;
        }

        if (policy.stackMode() == BoosterStackMode.DURATION || policy.stackMode() == BoosterStackMode.REPLACE) {
            return Math.min(policy.maxMultiplier(), active.getFirst().multiplier());
        }

        if (policy.calculation() == BoosterCalculation.COMPOUND) {
            double product = 1.0;
            for (IslandBooster booster : active) {
                product *= booster.multiplier();
            }
            return Math.min(policy.maxMultiplier(), product);
        } else {
            double bonus = 0.0;
            for (IslandBooster booster : active) {
                bonus += Math.max(0.0, booster.multiplier() - 1.0);
            }
            double total = 1.0 + bonus;
            return Math.min(policy.maxMultiplier(), total);
        }
    }

    /**
     * Everything a window needs to draw an island's boosters, read once.
     *
     * <p>The booster window asked for the paused flag, then every active booster, then the active
     * boosters of each of six categories, then the effective multiplier of each of those, and every
     * one of those was its own query: fourteen of them, on the thread that owns the player, every
     * time somebody opened it. All fourteen answers come out of one read of the island's boosters.
     */
    public record BoosterOverview(
            boolean paused, List<IslandBooster> active, Map<BoosterCategory, Double> effectiveMultipliers) {

        public BoosterOverview {
            active = List.copyOf(active);
            effectiveMultipliers = Map.copyOf(effectiveMultipliers);
        }

        /** The active boosters of one category, in the order they were read. */
        public List<IslandBooster> activeIn(BoosterCategory category) {
            return active.stream()
                    .filter(booster -> booster.category() == category)
                    .toList();
        }

        /** What one category currently multiplies by, one when it has nothing or is switched off. */
        public double multiplierOf(BoosterCategory category) {
            Double multiplier = effectiveMultipliers.get(category);
            return multiplier == null ? 1.0 : multiplier;
        }
    }

    /** Reads the island's boosters once and answers every question a window asks from that. */
    public BoosterOverview overview(IslandId islandId, Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(now, "now must not be null");

        List<IslandBooster> all = storagePort.findByIsland(islandId);
        List<IslandBooster> active = new ArrayList<>();
        boolean paused = pausedIslands.contains(islandId);
        for (IslandBooster booster : all) {
            if (booster.isPaused()) {
                paused = true;
            }
            if (booster.isActive(now) || booster.isPaused()) {
                active.add(booster);
            }
        }

        Map<BoosterCategory, Double> multipliers = new java.util.EnumMap<>(BoosterCategory.class);
        for (BoosterCategory category : BoosterCategory.values()) {
            multipliers.put(category, multiplierFrom(category, active, now));
        }
        return new BoosterOverview(paused, active, multipliers);
    }

    /** The same arithmetic {@link #getEffectiveMultiplier} does, over boosters already in hand. */
    private double multiplierFrom(BoosterCategory category, List<IslandBooster> boosters, Instant now) {
        CategoryBoosterPolicy policy = policy(category);
        if (!policy.enabled()) {
            return 1.0;
        }
        List<IslandBooster> active = boosters.stream()
                .filter(booster -> booster.category() == category)
                .filter(booster -> booster.isActive(now))
                .toList();
        if (active.isEmpty()) {
            return 1.0;
        }
        if (policy.stackMode() == BoosterStackMode.DURATION || policy.stackMode() == BoosterStackMode.REPLACE) {
            return Math.min(policy.maxMultiplier(), active.getFirst().multiplier());
        }
        if (policy.calculation() == BoosterCalculation.COMPOUND) {
            double product = 1.0;
            for (IslandBooster booster : active) {
                product *= booster.multiplier();
            }
            return Math.min(policy.maxMultiplier(), product);
        }
        double bonus = 0.0;
        for (IslandBooster booster : active) {
            bonus += Math.max(0.0, booster.multiplier() - 1.0);
        }
        return Math.min(policy.maxMultiplier(), 1.0 + bonus);
    }

    /** Retrieves all active boosters for the given island across all categories. */
    public List<IslandBooster> getActiveBoosters(IslandId islandId, Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(now, "now must not be null");

        List<IslandBooster> all = storagePort.findByIsland(islandId);
        List<IslandBooster> active = new ArrayList<>();
        for (IslandBooster booster : all) {
            if (booster.isActive(now)) {
                active.add(booster);
            }
        }
        return Collections.unmodifiableList(active);
    }

    /**
     * Retrieves active boosters for the given island and category.
     */
    public List<IslandBooster> getActiveBoosters(IslandId islandId, BoosterCategory category, Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(now, "now must not be null");

        List<IslandBooster> list = storagePort.findByIslandAndCategory(islandId, category);
        List<IslandBooster> active = new ArrayList<>();
        for (IslandBooster booster : list) {
            if (booster.isActive(now)) {
                active.add(booster);
            }
        }
        return Collections.unmodifiableList(active);
    }

    /**
     * Pauses or resumes the island's boosters to match who is actually on it.
     *
     * <p>A join and a quit on one island arrive on the same pool and used to race. The quit counted
     * nobody left and started pausing; the join counted somebody and found nothing paused yet, so it
     * resumed nothing and left; the pause then landed on an occupied island. Its boosters stopped
     * applying and stopped counting down, and the island's own record of whether it was paused said
     * the opposite of its rows. The other order burns paid booster time on an empty island, which is
     * the thing pause-when-empty exists to prevent.
     *
     * <p>So the count is taken again here, inside the lock, rather than carried in from whenever the
     * event fired. Whichever of the two runs second sees the truth and is the one that decides.
     *
     * @param onlineMembers how many members are on the island, asked inside the lock
     */
    public void followOccupancy(IslandId islandId, IntSupplier onlineMembers, Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(onlineMembers, "onlineMembers must not be null");
        Objects.requireNonNull(now, "now must not be null");

        boosterLock.inside(islandId, () -> {
            if (onlineMembers.getAsInt() > 0) {
                resumeInside(islandId, now);
            } else {
                pauseInside(islandId, now);
            }
        });
    }

    public void pauseBoosters(IslandId islandId, Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        boosterLock.inside(islandId, () -> pauseInside(islandId, now));
    }

    private void pauseInside(IslandId islandId, Instant now) {
        if (!pauseWhenEmptyEnabled) {
            return;
        }

        pausedIslands.add(islandId);
        List<IslandBooster> boosters = storagePort.findByIsland(islandId);
        List<IslandBooster> toSave = new ArrayList<>();
        for (IslandBooster booster : boosters) {
            if (booster.isActive(now) && !booster.isPaused()) {
                toSave.add(booster.withPause(now));
            }
        }
        if (!toSave.isEmpty()) {
            storagePort.saveAll(toSave);
        }
    }

    /** Resumes every paused booster on the island, held against a pause arriving at the same moment. */
    public void resumeBoosters(IslandId islandId, Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        boosterLock.inside(islandId, () -> resumeInside(islandId, now));
    }

    private void resumeInside(IslandId islandId, Instant now) {
        pausedIslands.remove(islandId);
        List<IslandBooster> boosters = storagePort.findByIsland(islandId);
        List<IslandBooster> toSave = new ArrayList<>();
        for (IslandBooster booster : boosters) {
            if (booster.isPaused()) {
                toSave.add(booster.withResume(now));
            }
        }
        if (!toSave.isEmpty()) {
            storagePort.saveAll(toSave);
        }
    }

    public boolean isIslandPaused(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        if (pausedIslands.contains(islandId)) {
            return true;
        }
        List<IslandBooster> boosters = storagePort.findByIsland(islandId);
        return boosters.stream().anyMatch(IslandBooster::isPaused);
    }

    /**
     * Purges expired, unpaused boosters from storage.
     */
    public int purgeExpired(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return storagePort.purgeExpired(now);
    }

    /**
     * Clears all boosters for the specified island (e.g. on island reset/deletion).
     */
    public void clearBoosters(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        pausedIslands.remove(islandId);
        storagePort.deleteByIsland(islandId);
    }

    public Optional<IslandBooster> findBooster(UUID boosterId) {
        Objects.requireNonNull(boosterId, "boosterId must not be null");
        return storagePort.findById(boosterId);
    }

    public void removeBooster(UUID boosterId) {
        Objects.requireNonNull(boosterId, "boosterId must not be null");
        storagePort.deleteById(boosterId);
    }
}

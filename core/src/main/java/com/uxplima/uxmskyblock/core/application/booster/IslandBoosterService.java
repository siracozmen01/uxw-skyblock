package com.uxplima.uxmskyblock.core.application.booster;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

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
     * Retrieves all active boosters for the given island across all categories.
     */
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
     * Pauses all active boosters for the specified island.
     */
    public void pauseBoosters(IslandId islandId, Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(now, "now must not be null");

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

    /**
     * Resumes all paused boosters for the specified island.
     */
    public void resumeBoosters(IslandId islandId, Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(now, "now must not be null");

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

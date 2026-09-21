package com.uxplima.uxmskyblock.core.application.home;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.application.lock.KeyedMutationLock;
import com.uxplima.uxmskyblock.core.domain.home.Home;
import com.uxplima.uxmskyblock.core.domain.home.HomeId;
import com.uxplima.uxmskyblock.core.domain.home.HomeLimitPolicy;
import com.uxplima.uxmskyblock.core.domain.home.HomeScope;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Domain application service managing multiple home locations and limit enforcement (Section 2.42).
 */
public final class HomeService {

    private final HomeStoragePort homeStoragePort;
    private final HomeLimitPolicy homeLimitPolicy;

    /**
     * Makes counting the homes and writing the next one one thing.
     *
     * <p>Keyed by the profile, because a home belongs to a player rather than to an island. Without
     * it a player with two clients types the command twice and keeps more homes than their
     * permission tier allows, and the count is the only thing standing in the way.
     */
    private final KeyedMutationLock<ProfileId> mutationLock = new KeyedMutationLock<>();

    public sealed interface SetHomeResult {
        record Success(Home home) implements SetHomeResult {}

        record LimitExceeded(int currentCount, int maxAllowed) implements SetHomeResult {}
    }

    public HomeService(HomeStoragePort homeStoragePort, HomeLimitPolicy homeLimitPolicy) {
        this.homeStoragePort = Objects.requireNonNull(homeStoragePort, "homeStoragePort must not be null");
        this.homeLimitPolicy = Objects.requireNonNull(homeLimitPolicy, "homeLimitPolicy must not be null");
    }

    public HomeService(HomeStoragePort homeStoragePort) {
        this(homeStoragePort, HomeLimitPolicy.defaultPolicy());
    }

    public SetHomeResult setHome(
            ProfileId profileId,
            IslandId islandId,
            String name,
            HomeScope scope,
            String worldName,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            int allowance) {

        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(worldName, "worldName must not be null");

        String normalizedName = name.trim().toLowerCase(java.util.Locale.ROOT);
        return mutationLock.inside(
                profileId,
                () -> writeHome(profileId, islandId, normalizedName, scope, worldName, x, y, z, yaw, pitch, allowance));
    }

    /** Counts what the player has, refuses or writes. Runs inside the profile's mutation lock. */
    private SetHomeResult writeHome(
            ProfileId profileId,
            IslandId islandId,
            String normalizedName,
            HomeScope scope,
            String worldName,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            int allowance) {
        Optional<Home> existing = homeStoragePort.findHome(profileId, normalizedName);

        if (existing.isEmpty()) {
            int currentCount = homeStoragePort.countHomes(profileId);
            int maxAllowed = homeLimitPolicy.maxHomesFor(allowance);
            if (currentCount >= maxAllowed) {
                return new SetHomeResult.LimitExceeded(currentCount, maxAllowed);
            }
        }

        Instant now = Instant.now();
        Home home = new Home(
                existing.map(Home::id).orElseGet(HomeId::random),
                profileId,
                islandId,
                normalizedName,
                scope,
                worldName,
                x,
                y,
                z,
                yaw,
                pitch,
                existing.map(Home::createdAt).orElse(now),
                now);

        homeStoragePort.saveHome(home);
        return new SetHomeResult.Success(home);
    }

    public Optional<Home> getHome(ProfileId profileId, String name) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        return homeStoragePort.findHome(profileId, name.trim().toLowerCase(java.util.Locale.ROOT));
    }

    public List<Home> listHomes(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        return homeStoragePort.findHomesByProfileId(profileId);
    }

    public boolean deleteHome(ProfileId profileId, String name) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        return homeStoragePort.deleteHome(profileId, name.trim().toLowerCase(java.util.Locale.ROOT));
    }
}

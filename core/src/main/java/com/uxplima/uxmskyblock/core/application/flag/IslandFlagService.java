package com.uxplima.uxmskyblock.core.application.flag;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import com.uxplima.uxmskyblock.core.application.island.IslandMutationLock;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import org.jspecify.annotations.Nullable;

/**
 * Reading and moving the switches that decide what may happen on an island.
 *
 * <p>Sixteen flags are defined and the protection listener reads every one of them on every event.
 * Nothing could change a single one: PvP was on or off according to a default nobody could move.
 */
public final class IslandFlagService {

    /** What a request to move a flag came back with. */
    public sealed interface FlagChange {
        /** The flag moved, and {@code enabled} is where it is now. */
        record Changed(String flag, boolean enabled) implements FlagChange {}

        /** No flag goes by that name. {@code available} lists the ones that do. */
        record UnknownFlag(String flag, String available) implements FlagChange {}

        /** The caller's role on this island does not carry SETTINGS_MODIFY. */
        record NotAllowed() implements FlagChange {}

        /** The island or its location is gone, so there is nothing to write back. */
        record IslandMissing() implements FlagChange {}
    }

    private final IslandStoragePort islandStoragePort;
    private final IslandMutationLock mutationLock;

    public IslandFlagService(IslandStoragePort islandStoragePort, IslandMutationLock mutationLock) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.mutationLock = Objects.requireNonNull(mutationLock, "mutationLock must not be null");
    }

    public IslandFlagService(IslandStoragePort islandStoragePort) {
        this(islandStoragePort, new IslandMutationLock());
    }

    /** Every flag on the island and where it stands, in an order a reader can scan. */
    public Map<String, Boolean> flagsOf(Island island) {
        Objects.requireNonNull(island, "island must not be null");
        return new TreeMap<>(island.flags().values());
    }

    /** The flag names, lower case and comma separated, for telling a caller what they may name. */
    public String flagNames(Island island) {
        Objects.requireNonNull(island, "island must not be null");
        return String.join(
                ", ",
                flagsOf(island).keySet().stream()
                        .map(name -> name.toLowerCase(Locale.ROOT))
                        .toList());
    }

    /**
     * Turns one flag the other way, if the caller's role may.
     *
     * <p>The owner always may. Anybody else needs {@link IslandPermission#SETTINGS_MODIFY}, which is
     * carried by whichever roles the island grants it to: this asks the permission rather than
     * naming a rank, so an island with three roles and one with nine behave the same.
     */
    public FlagChange toggle(Island island, ProfileId actorProfileId, String rawFlagName) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(actorProfileId, "actorProfileId must not be null");
        Objects.requireNonNull(rawFlagName, "rawFlagName must not be null");

        String flag = rawFlagName.toUpperCase(Locale.ROOT);
        if (!island.flags().values().containsKey(flag)) {
            return new FlagChange.UnknownFlag(rawFlagName.toLowerCase(Locale.ROOT), flagNames(island));
        }
        if (!mayModifySettings(island, actorProfileId)) {
            return new FlagChange.NotAllowed();
        }

        // The island the caller handed in was read before they asked, and everything between then
        // and now is somebody else's change. The write is built on a read taken inside the lock, so
        // a flag toggled while a member is joining does not erase the membership.
        return mutationLock.inside(island.id(), () -> write(island.id(), flag, null));
    }

    /**
     * Puts one flag where the caller asked, rather than the other way from where it is.
     *
     * <p>A lock command has to be this and not a toggle. An owner who types {@code /is lock} twice
     * because the first one scrolled past means the island to be locked both times, and a toggle
     * would unlock it.
     */
    public FlagChange set(Island island, ProfileId actorProfileId, String rawFlagName, boolean enabled) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(actorProfileId, "actorProfileId must not be null");
        Objects.requireNonNull(rawFlagName, "rawFlagName must not be null");

        String flag = rawFlagName.toUpperCase(Locale.ROOT);
        if (!island.flags().values().containsKey(flag)) {
            return new FlagChange.UnknownFlag(rawFlagName.toLowerCase(Locale.ROOT), flagNames(island));
        }
        if (!mayModifySettings(island, actorProfileId)) {
            return new FlagChange.NotAllowed();
        }

        return mutationLock.inside(island.id(), () -> write(island.id(), flag, enabled));
    }

    /**
     * Reads the island fresh, moves one flag on it and writes it back.
     *
     * <p>Runs inside the mutation lock. A null {@code enabled} means the other way from wherever the
     * flag is now, which is what a toggle is, and reading that inside the lock is the whole reason
     * this is a second read rather than a change to the island the caller already held.
     */
    private FlagChange write(IslandId islandId, String flag, @Nullable Boolean enabled) {
        Optional<Island> optFresh = islandStoragePort.findIslandById(islandId);
        Optional<IslandLocation> optLocation = islandStoragePort.findLocationByIslandId(islandId);
        if (optFresh.isEmpty() || optLocation.isEmpty()) {
            return new FlagChange.IslandMissing();
        }

        Island fresh = optFresh.get();
        boolean target = enabled != null
                ? enabled
                : !Boolean.TRUE.equals(fresh.flags().values().get(flag));
        islandStoragePort.saveIsland(fresh.withFlags(fresh.flags().withFlag(flag, target)), optLocation.get());
        return new FlagChange.Changed(flag.toLowerCase(Locale.ROOT), target);
    }

    /** Reads the island fresh, for a caller that holds only its id. */
    public Optional<Island> findIsland(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return islandStoragePort.findIslandById(islandId);
    }

    private static boolean mayModifySettings(Island island, ProfileId profileId) {
        if (island.ownerProfileId().equals(profileId)) {
            return true;
        }
        IslandMember member = island.members().get(profileId);
        return member != null && member.role().permissions().contains(IslandPermission.SETTINGS_MODIFY);
    }
}

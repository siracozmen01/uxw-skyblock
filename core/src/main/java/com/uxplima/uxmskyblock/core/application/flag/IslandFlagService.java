package com.uxplima.uxmskyblock.core.application.flag;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;

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

    public IslandFlagService(IslandStoragePort islandStoragePort) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
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

        Optional<IslandLocation> optLocation = islandStoragePort.findLocationByIslandId(island.id());
        if (optLocation.isEmpty()) {
            return new FlagChange.IslandMissing();
        }

        boolean enabled = !Boolean.TRUE.equals(island.flags().values().get(flag));
        islandStoragePort.saveIsland(island.withFlags(island.flags().withFlag(flag, enabled)), optLocation.get());
        return new FlagChange.Changed(flag.toLowerCase(Locale.ROOT), enabled);
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

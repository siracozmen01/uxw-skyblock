package com.uxplima.uxmskyblock.core.application.membership;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.uxplima.uxmskyblock.core.application.island.IslandMutationLock;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;

/**
 * The roles an island's owner makes and takes away beside the four every island starts with.
 *
 * <p>An island had owner, co-owner, moderator and member, and an owner who wanted a builder who may place blocks
 * and touch no chest had no role to give them: a permission moved on the member role moved it for every member.
 * An owner makes a role here. It starts with what the island's members may do, sits above the members and below
 * the moderators, and is edited in the same window as the others. Taking one away moves whoever held it back to
 * member.
 */
public final class IslandRoleShaper {

    /** What a role's name may be: what a player types after {@code /is role <player>}. */
    public static final Pattern NAME = Pattern.compile("[a-z0-9_]{2,16}");

    /** Where the first role an owner makes sits: above the members, below the moderators. */
    static final int FIRST_WEIGHT = IslandRole.MEMBER.weight() + 1;

    /** Words the permission command reads in the place of a role, so no role may take one. */
    private static final Set<String> RESERVED = Set.of("list", "create", "delete");

    /** What a request to make or take away a role came back with. */
    public sealed interface Outcome {

        /** The island has the role now. */
        record Created(String roleId) implements Outcome {}

        /** The role is gone, and {@code moved} members who held it are members again. */
        record Deleted(String roleId, int moved) implements Outcome {}

        /** Only the owner makes or takes away a role. */
        record NotOwner() implements Outcome {}

        /** The caller has no island. */
        record NoIsland() implements Outcome {}

        /** The name is not one a role can take. */
        record BadName(String name) implements Outcome {}

        /** The island has a role of that name already. */
        record Taken(String roleId) implements Outcome {}

        /** The island has as many roles of its own as the owner may make. */
        record LimitReached(int limit) implements Outcome {}

        /** The island has no role by that name. {@code available} lists the ones the owner made. */
        record UnknownRole(String roleId, String available) implements Outcome {}

        /** The role is one every island starts with, which is not taken away. */
        record Shipped(String roleId) implements Outcome {}
    }

    private final IslandStoragePort islandStoragePort;
    private final IslandMutationLock mutationLock;
    private final Consumer<IslandId> rolesChanged;

    /** @param rolesChanged told the island whose roles changed, once the change is saved */
    public IslandRoleShaper(
            IslandStoragePort islandStoragePort, IslandMutationLock mutationLock, Consumer<IslandId> rolesChanged) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.mutationLock = Objects.requireNonNull(mutationLock, "mutationLock must not be null");
        this.rolesChanged = Objects.requireNonNull(rolesChanged, "rolesChanged must not be null");
    }

    /** Whether {@code role} is one an owner made, rather than one every island starts with. */
    public static boolean isOwnMade(IslandRole role) {
        return IslandRole.byId(role.id()).isEmpty();
    }

    /**
     * Makes the role {@code rawName} on the caller's island, when the caller owns it and the island has fewer roles
     * of its own than {@code allowance}.
     */
    public Outcome create(ProfileId actor, String rawName, int allowance) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(rawName, "rawName must not be null");
        String name = rawName.strip().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches() || RESERVED.contains(name)) {
            return new Outcome.BadName(rawName.strip());
        }
        String roleId = name.toUpperCase(Locale.ROOT);
        Optional<Island> owned = ownedBy(actor);
        if (owned.isEmpty()) {
            return islandOf(actor).isEmpty() ? new Outcome.NoIsland() : new Outcome.NotOwner();
        }
        Outcome outcome = mutationLock.inside(owned.get().id(), () -> {
            Optional<Island> optFresh =
                    islandStoragePort.findIslandById(owned.get().id());
            Optional<IslandLocation> optLocation =
                    islandStoragePort.findLocationByIslandId(owned.get().id());
            if (optFresh.isEmpty() || optLocation.isEmpty()) {
                return new Outcome.NoIsland();
            }
            Island fresh = optFresh.get();
            if (fresh.roles().containsKey(roleId) || IslandRole.byId(roleId).isPresent()) {
                return new Outcome.Taken(name);
            }
            long made = fresh.roles().values().stream()
                    .filter(IslandRoleShaper::isOwnMade)
                    .count();
            if (made >= allowance) {
                return new Outcome.LimitReached(allowance);
            }
            IslandRole members = fresh.roles().getOrDefault(IslandRole.MEMBER.id(), IslandRole.MEMBER);
            Set<IslandPermission> permissions = EnumSet.noneOf(IslandPermission.class);
            permissions.addAll(members.permissions());
            int weight = FIRST_WEIGHT
                    + fresh.roles().values().stream()
                            .filter(IslandRoleShaper::isOwnMade)
                            .mapToInt(role -> role.weight() - FIRST_WEIGHT + 1)
                            .max()
                            .orElse(0);
            islandStoragePort.saveIsland(
                    fresh.withRole(new IslandRole(roleId, weight, name, permissions, false)), optLocation.get());
            return new Outcome.Created(name);
        });
        if (outcome instanceof Outcome.Created) {
            rolesChanged.accept(owned.get().id());
        }
        return outcome;
    }

    /** Takes the role {@code rawRoleId} away from the caller's island, and makes whoever held it a member. */
    public Outcome delete(ProfileId actor, String rawRoleId) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(rawRoleId, "rawRoleId must not be null");
        String name = rawRoleId.strip().toLowerCase(Locale.ROOT);
        String roleId = name.toUpperCase(Locale.ROOT);
        Optional<Island> owned = ownedBy(actor);
        if (owned.isEmpty()) {
            return islandOf(actor).isEmpty() ? new Outcome.NoIsland() : new Outcome.NotOwner();
        }
        if (IslandRole.byId(roleId).isPresent()) {
            return new Outcome.Shipped(name);
        }
        Outcome outcome = mutationLock.inside(owned.get().id(), () -> {
            Optional<Island> optFresh =
                    islandStoragePort.findIslandById(owned.get().id());
            Optional<IslandLocation> optLocation =
                    islandStoragePort.findLocationByIslandId(owned.get().id());
            if (optFresh.isEmpty() || optLocation.isEmpty()) {
                return new Outcome.NoIsland();
            }
            Island fresh = optFresh.get();
            if (!fresh.roles().containsKey(roleId)) {
                return new Outcome.UnknownRole(name, ownMadeNames(fresh));
            }
            IslandRole members = fresh.roles().getOrDefault(IslandRole.MEMBER.id(), IslandRole.MEMBER);
            Island updated = fresh;
            int moved = 0;
            for (IslandMember member : fresh.members().values()) {
                if (member.role().id().equals(roleId)) {
                    updated = updated.addMember(
                            new IslandMember(member.playerUuid(), member.profileId(), members, member.joinedAt()));
                    moved++;
                }
            }
            islandStoragePort.saveIsland(updated.withoutRole(roleId), optLocation.get());
            return new Outcome.Deleted(name, moved);
        });
        if (outcome instanceof Outcome.Deleted) {
            rolesChanged.accept(owned.get().id());
        }
        return outcome;
    }

    /** The roles the owner made on {@code island}, lower case and comma separated, or a dash for none. */
    static String ownMadeNames(Island island) {
        return island.roles().values().stream()
                .filter(IslandRoleShaper::isOwnMade)
                .map(role -> role.id().toLowerCase(Locale.ROOT))
                .sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse("-");
    }

    private Optional<Island> ownedBy(ProfileId actor) {
        return islandOf(actor).filter(island -> island.ownerProfileId().equals(actor));
    }

    private Optional<Island> islandOf(ProfileId profileId) {
        return islandStoragePort.findIslandIdByProfileId(profileId).flatMap(islandStoragePort::findIslandById);
    }
}

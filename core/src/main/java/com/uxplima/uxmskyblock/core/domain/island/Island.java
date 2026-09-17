package com.uxplima.uxmskyblock.core.domain.island;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Pure domain Aggregate Root for an Island.
 */
public record Island(
        IslandId id,
        IslandBounds bounds,
        PlayerUuid ownerPlayerUuid,
        ProfileId ownerProfileId,
        Map<ProfileId, IslandMember> members,
        Map<String, IslandRole> roles,
        IslandFlags flags,
        Instant createdAt) {

    public Island {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(bounds, "bounds must not be null");
        Objects.requireNonNull(ownerPlayerUuid, "ownerPlayerUuid must not be null");
        Objects.requireNonNull(ownerProfileId, "ownerProfileId must not be null");
        Objects.requireNonNull(members, "members must not be null");
        Objects.requireNonNull(roles, "roles must not be null");
        Objects.requireNonNull(flags, "flags must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        members = Collections.unmodifiableMap(new HashMap<>(members));
        roles = Collections.unmodifiableMap(new HashMap<>(roles));

        if (!members.containsKey(ownerProfileId)) {
            throw new IllegalArgumentException("Owner profile must be present in members map");
        }
    }

    public static Island create(
            IslandId id, IslandBounds bounds, PlayerUuid ownerPlayerUuid, ProfileId ownerProfileId, Instant createdAt) {

        IslandMember ownerMember = new IslandMember(ownerPlayerUuid, ownerProfileId, IslandRole.OWNER, createdAt);

        Map<ProfileId, IslandMember> members = new HashMap<>();
        members.put(ownerProfileId, ownerMember);

        Map<String, IslandRole> defaultRoles = new HashMap<>();
        defaultRoles.put(IslandRole.OWNER.id(), IslandRole.OWNER);
        defaultRoles.put(IslandRole.CO_OWNER.id(), IslandRole.CO_OWNER);
        defaultRoles.put(IslandRole.MODERATOR.id(), IslandRole.MODERATOR);
        defaultRoles.put(IslandRole.MEMBER.id(), IslandRole.MEMBER);
        defaultRoles.put(IslandRole.VISITOR.id(), IslandRole.VISITOR);

        return new Island(
                id, bounds, ownerPlayerUuid, ownerProfileId, members, defaultRoles, IslandFlags.defaults(), createdAt);
    }

    public boolean isOwner(ProfileId profileId) {
        return ownerProfileId.equals(profileId);
    }

    public boolean isMember(ProfileId profileId) {
        return members.containsKey(profileId);
    }

    public Optional<IslandMember> member(ProfileId profileId) {
        return Optional.ofNullable(members.get(profileId));
    }

    public IslandRole roleOf(ProfileId profileId) {
        IslandMember m = members.get(profileId);
        return m != null ? m.role() : IslandRole.VISITOR;
    }

    public boolean hasPermission(ProfileId profileId, IslandPermission permission) {
        return roleOf(profileId).hasPermission(permission);
    }

    public Island addMember(IslandMember member) {
        Objects.requireNonNull(member, "member must not be null");
        Map<ProfileId, IslandMember> copy = new HashMap<>(members);
        copy.put(member.profileId(), member);
        return new Island(id, bounds, ownerPlayerUuid, ownerProfileId, copy, roles, flags, createdAt);
    }

    public Island removeMember(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        if (ownerProfileId.equals(profileId)) {
            throw new IllegalArgumentException("Cannot remove island owner from members");
        }
        Map<ProfileId, IslandMember> copy = new HashMap<>(members);
        copy.remove(profileId);
        return new Island(id, bounds, ownerPlayerUuid, ownerProfileId, copy, roles, flags, createdAt);
    }

    public Island withFlags(IslandFlags newFlags) {
        Objects.requireNonNull(newFlags, "newFlags must not be null");
        return new Island(id, bounds, ownerPlayerUuid, ownerProfileId, members, roles, newFlags, createdAt);
    }

    public Island withBounds(IslandBounds newBounds) {
        Objects.requireNonNull(newBounds, "newBounds must not be null");
        return new Island(id, newBounds, ownerPlayerUuid, ownerProfileId, members, roles, flags, createdAt);
    }

    public Island transferOwnership(PlayerUuid newOwnerPlayerUuid, ProfileId newOwnerProfileId) {
        Objects.requireNonNull(newOwnerPlayerUuid, "newOwnerPlayerUuid must not be null");
        Objects.requireNonNull(newOwnerProfileId, "newOwnerProfileId must not be null");

        Map<ProfileId, IslandMember> copy = new HashMap<>(members);
        // Demote previous owner to CO_OWNER
        IslandMember oldOwner = copy.get(ownerProfileId);
        if (oldOwner != null) {
            copy.put(ownerProfileId, oldOwner.withRole(IslandRole.CO_OWNER));
        }
        // Promote new owner
        IslandMember newOwner = copy.get(newOwnerProfileId);
        Instant joined = newOwner != null ? newOwner.joinedAt() : Instant.now();
        copy.put(newOwnerProfileId, new IslandMember(newOwnerPlayerUuid, newOwnerProfileId, IslandRole.OWNER, joined));

        return new Island(id, bounds, newOwnerPlayerUuid, newOwnerProfileId, copy, roles, flags, createdAt);
    }
}

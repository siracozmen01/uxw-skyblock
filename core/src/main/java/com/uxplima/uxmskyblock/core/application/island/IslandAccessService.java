package com.uxplima.uxmskyblock.core.application.island;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;

/**
 * Domain service enforcing island permissions and access policies.
 */
public final class IslandAccessService {

    public boolean canBreak(Island island, ProfileId profileId) {
        return checkPermission(island, profileId, IslandPermission.BLOCK_BREAK);
    }

    public boolean canPlace(Island island, ProfileId profileId) {
        return checkPermission(island, profileId, IslandPermission.BLOCK_PLACE);
    }

    public boolean canOpenContainer(Island island, ProfileId profileId) {
        return checkPermission(island, profileId, IslandPermission.CHEST_OPEN);
    }

    public boolean canInteract(Island island, ProfileId profileId) {
        return checkPermission(island, profileId, IslandPermission.NATURAL_INTERACT);
    }

    public boolean canChangeBiome(Island island, ProfileId profileId) {
        return checkPermission(island, profileId, IslandPermission.BIOME_CHANGE);
    }

    public boolean canDepositBank(Island island, ProfileId profileId) {
        return checkPermission(island, profileId, IslandPermission.BANK_DEPOSIT);
    }

    public boolean canWithdrawBank(Island island, ProfileId profileId) {
        return checkPermission(island, profileId, IslandPermission.BANK_WITHDRAW);
    }

    public boolean canManageMembers(Island island, ProfileId profileId) {
        return checkPermission(island, profileId, IslandPermission.MEMBER_INVITE)
                || checkPermission(island, profileId, IslandPermission.MEMBER_KICK);
    }

    public boolean checkPermission(Island island, ProfileId profileId, IslandPermission permission) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(permission, "permission must not be null");

        if (island.isOwner(profileId)) {
            return true;
        }
        return island.hasPermission(profileId, permission);
    }
}

package com.uxplima.uxmskyblock.core.application.island;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandAccessServiceTest {

    private IslandAccessService accessService;
    private Island island;
    private ProfileId ownerProfileId;
    private ProfileId memberProfileId;
    private ProfileId visitorProfileId;

    @BeforeEach
    void setUp() {
        accessService = new IslandAccessService();

        PlayerUuid ownerUuid = new PlayerUuid(UUID.randomUUID());
        ownerProfileId = new ProfileId(UUID.randomUUID());

        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 50);
        island = Island.create(IslandId.of(UUID.randomUUID()), bounds, ownerUuid, ownerProfileId, Instant.now());

        PlayerUuid memberUuid = new PlayerUuid(UUID.randomUUID());
        memberProfileId = new ProfileId(UUID.randomUUID());
        IslandMember member = new IslandMember(memberUuid, memberProfileId, IslandRole.MEMBER, Instant.now());
        island = island.addMember(member);

        visitorProfileId = new ProfileId(UUID.randomUUID());
    }

    @Test
    @DisplayName("owner has all permissions unconditionally")
    void ownerHasAllPermissions() {
        assertThat(accessService.canBreak(island, ownerProfileId)).isTrue();
        assertThat(accessService.canPlace(island, ownerProfileId)).isTrue();
        assertThat(accessService.canOpenContainer(island, ownerProfileId)).isTrue();
        assertThat(accessService.canInteract(island, ownerProfileId)).isTrue();
        assertThat(accessService.canChangeBiome(island, ownerProfileId)).isTrue();
        assertThat(accessService.canDepositBank(island, ownerProfileId)).isTrue();
        assertThat(accessService.canWithdrawBank(island, ownerProfileId)).isTrue();
        assertThat(accessService.canManageMembers(island, ownerProfileId)).isTrue();
    }

    @Test
    @DisplayName("member has standard build and interaction permissions but not admin")
    void memberPermissions() {
        assertThat(accessService.canBreak(island, memberProfileId)).isTrue();
        assertThat(accessService.canPlace(island, memberProfileId)).isTrue();
        assertThat(accessService.canOpenContainer(island, memberProfileId)).isTrue();
        assertThat(accessService.canDepositBank(island, memberProfileId)).isTrue();

        // Member cannot change biome or withdraw bank by default
        assertThat(accessService.canWithdrawBank(island, memberProfileId)).isFalse();
        assertThat(accessService.canChangeBiome(island, memberProfileId)).isFalse();
        assertThat(accessService.canManageMembers(island, memberProfileId)).isFalse();
    }

    @Test
    @DisplayName("visitor has no build or container permissions")
    void visitorPermissions() {
        assertThat(accessService.canBreak(island, visitorProfileId)).isFalse();
        assertThat(accessService.canPlace(island, visitorProfileId)).isFalse();
        assertThat(accessService.canOpenContainer(island, visitorProfileId)).isFalse();
        assertThat(accessService.canWithdrawBank(island, visitorProfileId)).isFalse();
        assertThat(accessService.canChangeBiome(island, visitorProfileId)).isFalse();
        assertThat(accessService.canManageMembers(island, visitorProfileId)).isFalse();
    }
}

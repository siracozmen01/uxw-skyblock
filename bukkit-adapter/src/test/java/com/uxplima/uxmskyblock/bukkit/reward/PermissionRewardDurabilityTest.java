package com.uxplima.uxmskyblock.bukkit.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.OfflinePlayer;

import com.uxplima.uxmskyblock.bukkit.reward.PermissionRewardDeliveryHandler.PermissionService;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.profile.ProfileSwitchPort;
import com.uxplima.uxmskyblock.core.application.reward.RewardDeliveryHandler.DeliveryResult;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentOperationId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentState;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PermissionRewardDurabilityTest {

    private ServerMock server;
    private PlayerSessionCoordinator sessionCoordinator;
    private ProfileSwitchPort profileSwitchPort;
    private PermissionService vaultPermission;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        sessionCoordinator = mock(PlayerSessionCoordinator.class);
        profileSwitchPort = mock(ProfileSwitchPort.class);
        vaultPermission = mock(PermissionService.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("Provider unavailable: fails closed with pending status so reward remains in inbox")
    void providerUnavailableFailsClosed() {
        PermissionRewardDeliveryHandler handler =
                new PermissionRewardDeliveryHandler(() -> Optional.empty(), profileSwitchPort, sessionCoordinator);

        ProfileId profileId = new ProfileId(UUID.randomUUID());
        RewardGrant grant = createGrant(profileId);
        RewardGrantComponent component = createComponent(grant.grantId(), "uxmskyblock.island.fly");

        DeliveryResult result = handler.deliver(grant, component, profileId);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("unavailable");
    }

    @Test
    @DisplayName("Unresolvable profile: fails closed with pending status")
    void unresolvableProfileFailsClosed() {
        when(profileSwitchPort.resolvePlayerUuid(any())).thenReturn(Optional.empty());

        PermissionRewardDeliveryHandler handler = new PermissionRewardDeliveryHandler(
                () -> Optional.of(vaultPermission), profileSwitchPort, sessionCoordinator);

        ProfileId profileId = new ProfileId(UUID.randomUUID());
        RewardGrant grant = createGrant(profileId);
        RewardGrantComponent component = createComponent(grant.grantId(), "uxmskyblock.island.fly");

        DeliveryResult result = handler.deliver(grant, component, profileId);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("cannot be resolved to a player account");
        verify(vaultPermission, never()).add(any(OfflinePlayer.class), any());
    }

    @Test
    @DisplayName("Durable delivery resolves canonical PlayerUuid and grants permission via VaultPermission")
    void durableDeliveryGrantsPermission() {
        PlayerMock player = server.addPlayer();
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        ProfileId profileId = new ProfileId(UUID.randomUUID());

        when(profileSwitchPort.resolvePlayerUuid(profileId)).thenReturn(Optional.of(playerUuid));
        when(vaultPermission.has(any(OfflinePlayer.class), any())).thenReturn(false, true);
        when(vaultPermission.add(any(OfflinePlayer.class), any())).thenReturn(true);

        PermissionRewardDeliveryHandler handler = new PermissionRewardDeliveryHandler(
                () -> Optional.of(vaultPermission), profileSwitchPort, sessionCoordinator);

        RewardGrant grant = createGrant(profileId);
        RewardGrantComponent component = createComponent(grant.grantId(), "uxmskyblock.donor.tier1");

        DeliveryResult result = handler.deliver(grant, component, profileId);

        assertThat(result.success()).isTrue();
        assertThat(result.journalOperationId())
                .isEqualTo(component.componentOperationId().value());
        verify(vaultPermission)
                .add(any(OfflinePlayer.class), org.mockito.ArgumentMatchers.eq("uxmskyblock.donor.tier1"));
    }

    @Test
    @DisplayName("Idempotent retry: when permission is already present, returns success without re-adding")
    void idempotentRetrySucceedsWithoutReAdd() {
        PlayerMock player = server.addPlayer();
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        ProfileId profileId = new ProfileId(UUID.randomUUID());

        when(profileSwitchPort.resolvePlayerUuid(profileId)).thenReturn(Optional.of(playerUuid));
        when(vaultPermission.has(any(OfflinePlayer.class), any())).thenReturn(true);

        PermissionRewardDeliveryHandler handler = new PermissionRewardDeliveryHandler(
                () -> Optional.of(vaultPermission), profileSwitchPort, sessionCoordinator);

        RewardGrant grant = createGrant(profileId);
        RewardGrantComponent component = createComponent(grant.grantId(), "uxmskyblock.donor.tier1");

        DeliveryResult result = handler.deliver(grant, component, profileId);

        assertThat(result.success()).isTrue();
        verify(vaultPermission, never()).add(any(OfflinePlayer.class), any());
    }

    private RewardGrant createGrant(ProfileId profileId) {
        return new RewardGrant(
                new RewardGrantId(UUID.randomUUID()),
                profileId,
                "QUEST_REWARD",
                "quest-perm-1",
                RewardGrantState.CLAIMING,
                List.of(),
                null,
                null,
                Instant.now(),
                Instant.now());
    }

    private RewardGrantComponent createComponent(RewardGrantId grantId, String permission) {
        return new RewardGrantComponent(
                UUID.randomUUID(),
                grantId,
                0,
                new RewardComponentOperationId(UUID.randomUUID()),
                RewardComponentType.PERMISSION,
                "uxm:permission",
                1,
                "{\"permission\":\"" + permission + "\"}",
                RewardComponentState.PENDING,
                null,
                Instant.now());
    }
}

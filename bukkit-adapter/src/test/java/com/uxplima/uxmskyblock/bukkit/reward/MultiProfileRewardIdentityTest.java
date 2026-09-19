package com.uxplima.uxmskyblock.bukkit.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.OfflinePlayer;

import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.core.application.economy.EconomySagaPort;
import com.uxplima.uxmskyblock.core.application.profile.ProfileSwitchPort;
import com.uxplima.uxmskyblock.core.application.reward.RewardDeliveryHandler.DeliveryResult;
import com.uxplima.uxmskyblock.core.domain.economy.EconomySagaRecord;
import com.uxplima.uxmskyblock.core.domain.economy.SagaId;
import com.uxplima.uxmskyblock.core.domain.economy.SagaState;
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

class MultiProfileRewardIdentityTest {

    private ServerMock server;
    private SkyblockEconomyBridge economyBridge;
    private EconomySagaPort sagaPort;
    private ProfileSwitchPort profileSwitchPort;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        economyBridge = mock(SkyblockEconomyBridge.class);
        sagaPort = mock(EconomySagaPort.class);
        profileSwitchPort = mock(ProfileSwitchPort.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("Vault delivery deposits strictly to canonical PlayerUuid, never casting ProfileId directly")
    void vaultDeliveryDepositsToCanonicalPlayerUuidNotProfileId() {
        PlayerMock player1 = server.addPlayer();
        PlayerMock player2 = server.addPlayer();

        PlayerUuid player1Uuid = new PlayerUuid(player1.getUniqueId());
        PlayerUuid player2Uuid = new PlayerUuid(player2.getUniqueId());

        ProfileId profileA = new ProfileId(UUID.randomUUID());
        ProfileId profileB = new ProfileId(UUID.randomUUID());

        // Ensure Profile UUID is completely different from Player UUID
        assertThat(profileA.value()).isNotEqualTo(player1Uuid.value());
        assertThat(profileB.value()).isNotEqualTo(player2Uuid.value());

        when(profileSwitchPort.resolvePlayerUuid(profileA)).thenReturn(Optional.of(player1Uuid));
        when(profileSwitchPort.resolvePlayerUuid(profileB)).thenReturn(Optional.of(player2Uuid));

        when(economyBridge.depositWallet(any(OfflinePlayer.class), anyDouble())).thenReturn(true);
        when(sagaPort.findSagaById(any())).thenReturn(Optional.empty());

        ExternalVaultRewardDeliveryHandler handler =
                new ExternalVaultRewardDeliveryHandler(() -> economyBridge, sagaPort, profileSwitchPort, null);

        // Deliver to Profile A
        RewardGrant grantA = createGrant(profileA);
        RewardGrantComponent compA = createComponent(grantA.grantId(), 500.0);
        DeliveryResult resA = handler.deliver(grantA, compA, profileA);

        assertThat(resA.success()).isTrue();
        verify(economyBridge)
                .depositWallet(
                        org.mockito.ArgumentMatchers.argThat(
                                (OfflinePlayer p) -> p.getUniqueId().equals(player1Uuid.value())),
                        eq(500.0));
        verify(economyBridge, never())
                .depositWallet(
                        org.mockito.ArgumentMatchers.argThat(
                                (OfflinePlayer p) -> p.getUniqueId().equals(player2Uuid.value())),
                        anyDouble());

        // Deliver to Profile B
        RewardGrant grantB = createGrant(profileB);
        RewardGrantComponent compB = createComponent(grantB.grantId(), 750.0);
        DeliveryResult resB = handler.deliver(grantB, compB, profileB);

        assertThat(resB.success()).isTrue();
        verify(economyBridge)
                .depositWallet(
                        org.mockito.ArgumentMatchers.argThat(
                                (OfflinePlayer p) -> p.getUniqueId().equals(player2Uuid.value())),
                        eq(750.0));
    }

    @Test
    @DisplayName("External vault idempotency: completed saga returns success without depositing again")
    void sagaAlreadyCommittedDoesNotDepositAgain() {
        PlayerMock player = server.addPlayer();
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        ProfileId profile = new ProfileId(UUID.randomUUID());

        when(profileSwitchPort.resolvePlayerUuid(profile)).thenReturn(Optional.of(playerUuid));

        RewardGrant grant = createGrant(profile);
        RewardGrantComponent comp = createComponent(grant.grantId(), 100.0);
        SagaId sagaId = new SagaId(comp.componentOperationId().value().toString());

        EconomySagaRecord committedSaga = EconomySagaRecord.start(
                        sagaId,
                        playerUuid,
                        profile,
                        new com.uxplima.uxmskyblock.core.domain.identity.IslandId(UUID.randomUUID()),
                        com.uxplima.uxmskyblock.core.domain.economy.SagaType.DEPOSIT,
                        10000L,
                        "VAULT",
                        Instant.now().plusSeconds(60),
                        Instant.now())
                .withState(SagaState.COMMITTED, Instant.now());

        when(sagaPort.findSagaById(sagaId)).thenReturn(Optional.of(committedSaga));

        ExternalVaultRewardDeliveryHandler handler =
                new ExternalVaultRewardDeliveryHandler(() -> economyBridge, sagaPort, profileSwitchPort, null);

        DeliveryResult res = handler.deliver(grant, comp, profile);

        assertThat(res.success()).isTrue();
        assertThat(res.journalOperationId())
                .isEqualTo(comp.componentOperationId().value());
        verify(economyBridge, never()).depositWallet(any(), anyDouble());
    }

    private static double eq(double val) {
        return org.mockito.ArgumentMatchers.doubleThat(d -> Math.abs(d - val) < 0.001);
    }

    private RewardGrant createGrant(ProfileId profileId) {
        return new RewardGrant(
                new RewardGrantId(UUID.randomUUID()),
                profileId,
                "QUEST_REWARD",
                "quest-vault-1",
                RewardGrantState.CLAIMING,
                List.of(),
                null,
                null,
                Instant.now(),
                Instant.now());
    }

    private RewardGrantComponent createComponent(RewardGrantId grantId, double amount) {
        return new RewardGrantComponent(
                UUID.randomUUID(),
                grantId,
                0,
                new RewardComponentOperationId(UUID.randomUUID()),
                RewardComponentType.EXTERNAL_VAULT,
                "uxm:vault_deposit",
                1,
                "{\"amount\":" + amount + "}",
                RewardComponentState.PENDING,
                null,
                Instant.now());
    }
}

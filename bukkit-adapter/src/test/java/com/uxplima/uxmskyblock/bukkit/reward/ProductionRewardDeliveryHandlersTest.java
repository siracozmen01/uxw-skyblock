package com.uxplima.uxmskyblock.bukkit.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.OfflinePlayer;

import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryMutationJournalPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.reward.RewardDeliveryHandler.DeliveryResult;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransaction;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentOperationId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentState;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantState;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ProductionRewardDeliveryHandlersTest {

    private ServerMock server;
    private PlayerSessionCoordinator sessionCoordinator;
    private InventoryMutationJournalPort journalPort;
    private IslandStoragePort islandStoragePort;
    private IslandBankService bankService;
    private ServerNodeId nodeId;

    private ProfileId recipientProfile;
    private RewardGrant testGrant;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        sessionCoordinator = mock(PlayerSessionCoordinator.class);
        journalPort = mock(InventoryMutationJournalPort.class);
        islandStoragePort = mock(IslandStoragePort.class);
        bankService = mock(IslandBankService.class);
        nodeId = new ServerNodeId("test-node");

        recipientProfile = new ProfileId(UUID.randomUUID());
        testGrant = new RewardGrant(
                new RewardGrantId(UUID.randomUUID()),
                recipientProfile,
                "QUEST_REWARD",
                "quest-1",
                RewardGrantState.CLAIMING,
                List.of(),
                null,
                null,
                Instant.now(),
                Instant.now());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("ItemRewardDeliveryHandler: Fails safely when recipient player is offline")
    void itemDeliveryFailsWhenPlayerOffline() {
        ItemRewardDeliveryHandler handler = new ItemRewardDeliveryHandler(sessionCoordinator, journalPort, nodeId);
        RewardGrantComponent component = new RewardGrantComponent(
                UUID.randomUUID(),
                testGrant.grantId(),
                0,
                new RewardComponentOperationId(UUID.randomUUID()),
                RewardComponentType.ITEM,
                "uxm:item_bundle",
                1,
                "{\"item\":\"DIAMOND_SWORD\",\"amount\":1}",
                RewardComponentState.PENDING,
                null,
                Instant.now());

        DeliveryResult result = handler.deliver(testGrant, component, recipientProfile);
        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("offline");
    }

    @Test
    @DisplayName("ItemRewardDeliveryHandler: Delivers to online player and commits journal mutation")
    void itemDeliverySucceedsWhenPlayerOnline() {
        PlayerMock player = server.addPlayer();
        when(sessionCoordinator.activeProfile(player.getUniqueId())).thenReturn(Optional.of(recipientProfile));
        PlayerSessionCoordinator.ActiveSession session = new PlayerSessionCoordinator.ActiveSession(
                new PlayerUuid(player.getUniqueId()), recipientProfile, 1L, 1L);
        when(sessionCoordinator.getActiveSession(player.getUniqueId())).thenReturn(session);

        when(journalPort.recordIntent(
                        any(), any(), any(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(InventoryMutationJournalOutcome.intentRecorded());
        when(journalPort.commitMutation(any(), any(), any(), anyLong(), anyLong(), any(), any()))
                .thenReturn(InventoryMutationJournalOutcome.success(2L));

        ItemRewardDeliveryHandler handler = new ItemRewardDeliveryHandler(sessionCoordinator, journalPort, nodeId);
        RewardGrantComponent component = new RewardGrantComponent(
                UUID.randomUUID(),
                testGrant.grantId(),
                0,
                new RewardComponentOperationId(UUID.randomUUID()),
                RewardComponentType.ITEM,
                "uxm:item_bundle",
                1,
                "{\"item\":\"DIAMOND\",\"amount\":5}",
                RewardComponentState.PENDING,
                null,
                Instant.now());

        DeliveryResult result = handler.deliver(testGrant, component, recipientProfile);
        assertThat(result.success()).isTrue();
        assertThat(result.journalOperationId())
                .isEqualTo(component.componentOperationId().value());
    }

    @Test
    @DisplayName("SqlCurrencyRewardDeliveryHandler: Fails when recipient has no active island")
    void currencyDeliveryFailsWhenNoIsland() {
        when(islandStoragePort.findIslandIdByProfileId(recipientProfile)).thenReturn(Optional.empty());

        SqlCurrencyRewardDeliveryHandler handler =
                new SqlCurrencyRewardDeliveryHandler(islandStoragePort, bankService, nodeId);

        RewardGrantComponent component = new RewardGrantComponent(
                UUID.randomUUID(),
                testGrant.grantId(),
                0,
                new RewardComponentOperationId(UUID.randomUUID()),
                RewardComponentType.SQL_CURRENCY,
                "uxm:currency_deposit",
                1,
                "{\"amount\":5000,\"currency\":\"PRIMARY\"}",
                RewardComponentState.PENDING,
                null,
                Instant.now());

        DeliveryResult result = handler.deliver(testGrant, component, recipientProfile);
        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("does not belong to an active island");
    }

    @Test
    @DisplayName("SqlCurrencyRewardDeliveryHandler: Succeeds when deposit completes with bank transaction")
    void currencyDeliverySucceedsWhenDepositApproved() {
        IslandId islandId = new IslandId(UUID.randomUUID());
        when(islandStoragePort.findIslandIdByProfileId(recipientProfile)).thenReturn(Optional.of(islandId));
        IslandBank bankMock = mock(IslandBank.class);
        BankTransaction txMock = mock(BankTransaction.class);
        when(bankService.depositToIsland(any(), any(), anyLong(), any(), any()))
                .thenReturn(new BankTransactionOutcome.Success(bankMock, txMock));

        com.uxplima.uxmskyblock.core.application.profile.ProfileSwitchPort profileSwitchPort =
                mock(com.uxplima.uxmskyblock.core.application.profile.ProfileSwitchPort.class);
        when(profileSwitchPort.resolvePlayerUuid(recipientProfile))
                .thenReturn(Optional.of(new PlayerUuid(UUID.randomUUID())));

        SqlCurrencyRewardDeliveryHandler handler =
                new SqlCurrencyRewardDeliveryHandler(islandStoragePort, bankService, nodeId, profileSwitchPort);

        RewardGrantComponent component = new RewardGrantComponent(
                UUID.randomUUID(),
                testGrant.grantId(),
                0,
                new RewardComponentOperationId(UUID.randomUUID()),
                RewardComponentType.SQL_CURRENCY,
                "uxm:currency_deposit",
                1,
                "{\"amount\":5000,\"currency\":\"PRIMARY\"}",
                RewardComponentState.PENDING,
                null,
                Instant.now());

        DeliveryResult result = handler.deliver(testGrant, component, recipientProfile);
        assertThat(result.success()).isTrue();
        assertThat(result.journalOperationId())
                .isEqualTo(component.componentOperationId().value());
    }

    @Test
    @DisplayName("ExternalVaultRewardDeliveryHandler: Fails safely when economy bridge is null")
    void vaultDeliveryFailsWhenBridgeNull() {
        ExternalVaultRewardDeliveryHandler handler = new ExternalVaultRewardDeliveryHandler(null);
        RewardGrantComponent component = new RewardGrantComponent(
                UUID.randomUUID(),
                testGrant.grantId(),
                0,
                new RewardComponentOperationId(UUID.randomUUID()),
                RewardComponentType.EXTERNAL_VAULT,
                "uxm:vault_deposit",
                1,
                "{\"amount\":250.0}",
                RewardComponentState.PENDING,
                null,
                Instant.now());

        DeliveryResult result = handler.deliver(testGrant, component, recipientProfile);
        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("bridge is not initialized");
    }

    @Test
    @DisplayName("ExternalVaultRewardDeliveryHandler: Succeeds when economy bridge deposits successfully")
    void vaultDeliverySucceedsWhenBridgeDeposits() {
        SkyblockEconomyBridge mockBridge = mock(SkyblockEconomyBridge.class);
        when(mockBridge.depositWallet(any(OfflinePlayer.class), anyDouble())).thenReturn(true);

        PlayerMock player = server.addPlayer();
        ProfileId playerProfile = new ProfileId(UUID.randomUUID());
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());

        com.uxplima.uxmskyblock.core.application.profile.ProfileSwitchPort profileSwitchPort =
                mock(com.uxplima.uxmskyblock.core.application.profile.ProfileSwitchPort.class);
        when(profileSwitchPort.resolvePlayerUuid(playerProfile)).thenReturn(Optional.of(playerUuid));

        ExternalVaultRewardDeliveryHandler handler =
                new ExternalVaultRewardDeliveryHandler(() -> mockBridge, null, profileSwitchPort, null);
        RewardGrantComponent component = new RewardGrantComponent(
                UUID.randomUUID(),
                testGrant.grantId(),
                0,
                new RewardComponentOperationId(UUID.randomUUID()),
                RewardComponentType.EXTERNAL_VAULT,
                "uxm:vault_deposit",
                1,
                "{\"amount\":250.0}",
                RewardComponentState.PENDING,
                null,
                Instant.now());

        RewardGrant grantForPlayer = new RewardGrant(
                new RewardGrantId(UUID.randomUUID()),
                playerProfile,
                "QUEST_REWARD",
                "quest-1",
                RewardGrantState.CLAIMING,
                List.of(),
                null,
                null,
                Instant.now(),
                Instant.now());

        DeliveryResult result = handler.deliver(grantForPlayer, component, playerProfile);
        assertThat(result.success()).isTrue();
        assertThat(result.journalOperationId())
                .isEqualTo(component.componentOperationId().value());
    }

    @Test
    @DisplayName(
            "PermissionRewardDeliveryHandler: Fails when provider unavailable or player offline, succeeds when online")
    void permissionDeliveryHandlesOnlineAndOffline() {
        PermissionRewardDeliveryHandler.PermissionService mockVaultPerm =
                mock(PermissionRewardDeliveryHandler.PermissionService.class);
        when(mockVaultPerm.add(any(OfflinePlayer.class), any())).thenReturn(true);
        when(mockVaultPerm.has(any(OfflinePlayer.class), any())).thenReturn(false, true);

        // 1. Provider unavailable fails closed (pending)
        PermissionRewardDeliveryHandler unavailableHandler =
                new PermissionRewardDeliveryHandler(() -> Optional.empty(), null, sessionCoordinator);

        RewardGrantComponent component = new RewardGrantComponent(
                UUID.randomUUID(),
                testGrant.grantId(),
                0,
                new RewardComponentOperationId(UUID.randomUUID()),
                RewardComponentType.PERMISSION,
                "uxm:permission",
                1,
                "{\"permission\":\"uxmskyblock.vip\"}",
                RewardComponentState.PENDING,
                null,
                Instant.now());

        DeliveryResult failRes = unavailableHandler.deliver(testGrant, component, recipientProfile);
        assertThat(failRes.success()).isFalse();
        assertThat(failRes.errorMessage()).contains("unavailable");

        // 2. Offline check with mock provider
        PermissionRewardDeliveryHandler handler =
                new PermissionRewardDeliveryHandler(() -> Optional.of(mockVaultPerm), null, sessionCoordinator);

        DeliveryResult offlineRes = handler.deliver(testGrant, component, recipientProfile);
        assertThat(offlineRes.success()).isFalse();
        assertThat(offlineRes.errorMessage()).contains("cannot be resolved to a player account");

        // 3. Online check
        PlayerMock player = server.addPlayer();
        when(sessionCoordinator.activeProfile(player.getUniqueId())).thenReturn(Optional.of(recipientProfile));

        DeliveryResult onlineRes = handler.deliver(testGrant, component, recipientProfile);
        assertThat(onlineRes.success()).isTrue();
    }

    @Test
    @DisplayName("CosmeticRewardDeliveryHandler: Grants and tracks cosmetics via ProfileCosmeticStoragePort")
    void cosmeticDeliveryGrantsAndTracks() {
        com.uxplima.uxmskyblock.core.application.cosmetic.ProfileCosmeticStoragePort cosmeticStoragePort =
                new com.uxplima.uxmskyblock.core.application.cosmetic.ProfileCosmeticStoragePort() {
                    private final java.util.Map<ProfileId, java.util.Set<String>> cosmetics =
                            new java.util.concurrent.ConcurrentHashMap<>();

                    @Override
                    public void grantCosmetic(ProfileId profileId, String cosmeticId, @Nullable String grantedBy) {
                        cosmetics
                                .computeIfAbsent(profileId, k -> new java.util.concurrent.ConcurrentSkipListSet<>())
                                .add(cosmeticId);
                    }

                    @Override
                    public boolean hasCosmetic(ProfileId profileId, String cosmeticId) {
                        return cosmetics
                                .getOrDefault(profileId, java.util.Set.of())
                                .contains(cosmeticId);
                    }

                    @Override
                    public java.util.Set<String> getCosmetics(ProfileId profileId) {
                        return java.util.Collections.unmodifiableSet(
                                cosmetics.getOrDefault(profileId, java.util.Set.of()));
                    }
                };

        CosmeticRewardDeliveryHandler handler = new CosmeticRewardDeliveryHandler(cosmeticStoragePort);
        RewardGrantComponent component = new RewardGrantComponent(
                UUID.randomUUID(),
                testGrant.grantId(),
                0,
                new RewardComponentOperationId(UUID.randomUUID()),
                RewardComponentType.COSMETIC,
                "uxm:cosmetic",
                1,
                "{\"cosmeticId\":\"neon_wings\"}",
                RewardComponentState.PENDING,
                null,
                Instant.now());

        DeliveryResult res = handler.deliver(testGrant, component, recipientProfile);
        assertThat(res.success()).isTrue();
        assertThat(handler.hasCosmetic(recipientProfile, "neon_wings")).isTrue();
        assertThat(handler.hasCosmetic(recipientProfile, "dragon_trail")).isFalse();
    }
}

package com.uxplima.uxmskyblock.bukkit.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryMutationJournalPort;
import com.uxplima.uxmskyblock.core.application.reward.RewardDeliveryHandler.DeliveryResult;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalRecord;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentOperationId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentState;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantState;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ItemRewardDeliveryHandlerCrashTest {

    private ServerMock server;
    private PlayerSessionCoordinator sessionCoordinator;
    private InventoryMutationJournalPort journalPort;
    private ServerNodeId nodeId;
    private ProfileId recipientProfile;
    private RewardGrant testGrant;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        sessionCoordinator = mock(PlayerSessionCoordinator.class);
        journalPort = mock(InventoryMutationJournalPort.class);
        nodeId = new ServerNodeId("test-node-1");

        recipientProfile = new ProfileId(UUID.randomUUID());
        testGrant = new RewardGrant(
                new RewardGrantId(UUID.randomUUID()),
                recipientProfile,
                "QUEST_REWARD",
                "quest-crash-1",
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
    @DisplayName("Crash/Failure at INTENT phase before live mutation: returns failure, inventory unchanged, no commit")
    void intentPhaseFailureAbortsWithoutLiveMutation() {
        PlayerMock player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.APPLE, 2));
        ItemStack[] initialInventory = player.getInventory().getContents().clone();

        when(sessionCoordinator.activeProfile(player.getUniqueId())).thenReturn(Optional.of(recipientProfile));
        PlayerSessionCoordinator.ActiveSession session = new PlayerSessionCoordinator.ActiveSession(
                new PlayerUuid(player.getUniqueId()), recipientProfile, 1L, 1L);
        when(sessionCoordinator.getActiveSession(player.getUniqueId())).thenReturn(session);

        when(journalPort.recordIntent(
                        any(), any(), any(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(InventoryMutationJournalOutcome.rejected("Write-ahead journal disk error"));

        ItemRewardDeliveryHandler handler = new ItemRewardDeliveryHandler(sessionCoordinator, journalPort, nodeId);
        RewardGrantComponent component = new RewardGrantComponent(
                UUID.randomUUID(),
                testGrant.grantId(),
                0,
                new RewardComponentOperationId(UUID.randomUUID()),
                RewardComponentType.ITEM,
                "uxm:item_bundle",
                1,
                "{\"item\":\"DIAMOND\",\"amount\":10}",
                RewardComponentState.PENDING,
                null,
                Instant.now());

        DeliveryResult result = handler.deliver(testGrant, component, recipientProfile);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("disk error");
        // Inventory must remain untouched
        assertThat(player.getInventory().getContents()).containsExactly(initialInventory);
        // commitMutation must never have been called
        verify(journalPort, never()).commitMutation(any(), any(), any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("Durable commit failure after live add: rolls back inventory, aborts intent, zero dropped overflow")
    void commitFailureRollsBackInventoryAndAbortsIntent() {
        PlayerMock player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.IRON_INGOT, 5));
        int initialIronCount = countItems(player, Material.IRON_INGOT);
        int initialDiamondCount = countItems(player, Material.DIAMOND);

        when(sessionCoordinator.activeProfile(player.getUniqueId())).thenReturn(Optional.of(recipientProfile));
        PlayerSessionCoordinator.ActiveSession session = new PlayerSessionCoordinator.ActiveSession(
                new PlayerUuid(player.getUniqueId()), recipientProfile, 1L, 1L);
        when(sessionCoordinator.getActiveSession(player.getUniqueId())).thenReturn(session);

        when(journalPort.recordIntent(
                        any(), any(), any(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(InventoryMutationJournalOutcome.intentRecorded());
        // Simulate DB crash / conflict during commit
        when(journalPort.commitMutation(any(), any(), any(), anyLong(), anyLong(), any(), any()))
                .thenReturn(InventoryMutationJournalOutcome.conflict("OptimisticLockException / node crash"));

        ItemRewardDeliveryHandler handler = new ItemRewardDeliveryHandler(sessionCoordinator, journalPort, nodeId);
        RewardGrantComponent component = new RewardGrantComponent(
                UUID.randomUUID(),
                testGrant.grantId(),
                0,
                new RewardComponentOperationId(UUID.randomUUID()),
                RewardComponentType.ITEM,
                "uxm:item_bundle",
                1,
                "{\"item\":\"DIAMOND\",\"amount\":64}",
                RewardComponentState.PENDING,
                null,
                Instant.now());

        DeliveryResult result = handler.deliver(testGrant, component, recipientProfile);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("OptimisticLockException");

        // Assert inventory rolled back cleanly: 0 diamonds added, iron count untouched
        assertThat(countItems(player, Material.DIAMOND)).isEqualTo(initialDiamondCount);
        assertThat(countItems(player, Material.IRON_INGOT)).isEqualTo(initialIronCount);

        // Assert abortIntent was recorded
        verify(journalPort, times(1))
                .abortIntent(
                        eq(new PlayerUuid(player.getUniqueId())),
                        eq(recipientProfile),
                        eq(nodeId),
                        anyLong(),
                        eq(new InventoryMutationOperationId(
                                component.componentOperationId().value())));

        // Assert zero dropped items on ground
        assertThat(player.getWorld().getEntitiesByClass(Item.class)).isEmpty();
    }

    @Test
    @DisplayName("Commit throws unhandled runtime exception: rolls back inventory and aborts intent safely")
    void commitExceptionRollsBackInventoryAndAbortsIntent() {
        PlayerMock player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.GOLD_INGOT, 3));

        when(sessionCoordinator.activeProfile(player.getUniqueId())).thenReturn(Optional.of(recipientProfile));
        PlayerSessionCoordinator.ActiveSession session = new PlayerSessionCoordinator.ActiveSession(
                new PlayerUuid(player.getUniqueId()), recipientProfile, 1L, 1L);
        when(sessionCoordinator.getActiveSession(player.getUniqueId())).thenReturn(session);

        when(journalPort.recordIntent(
                        any(), any(), any(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(InventoryMutationJournalOutcome.intentRecorded());
        when(journalPort.commitMutation(any(), any(), any(), anyLong(), anyLong(), any(), any()))
                .thenThrow(new RuntimeException("Connection pool exhausted"));

        ItemRewardDeliveryHandler handler = new ItemRewardDeliveryHandler(sessionCoordinator, journalPort, nodeId);
        RewardGrantComponent component = new RewardGrantComponent(
                UUID.randomUUID(),
                testGrant.grantId(),
                0,
                new RewardComponentOperationId(UUID.randomUUID()),
                RewardComponentType.ITEM,
                "uxm:item_bundle",
                1,
                "{\"item\":\"EMERALD\",\"amount\":16}",
                RewardComponentState.PENDING,
                null,
                Instant.now());

        DeliveryResult result = handler.deliver(testGrant, component, recipientProfile);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("Connection pool exhausted");
        assertThat(countItems(player, Material.EMERALD)).isEqualTo(0);
        assertThat(countItems(player, Material.GOLD_INGOT)).isEqualTo(3);
        verify(journalPort, times(1)).abortIntent(any(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName(
            "Retry after completed commit: detects already COMMITTED journal entry and succeeds idempotently without double add")
    void retryAfterCommittedSucceedsWithoutDoubleAdd() {
        PlayerMock player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 5));

        when(sessionCoordinator.activeProfile(player.getUniqueId())).thenReturn(Optional.of(recipientProfile));
        PlayerSessionCoordinator.ActiveSession session = new PlayerSessionCoordinator.ActiveSession(
                new PlayerUuid(player.getUniqueId()), recipientProfile, 1L, 1L);
        when(sessionCoordinator.getActiveSession(player.getUniqueId())).thenReturn(session);

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

        InventoryMutationOperationId opId = new InventoryMutationOperationId(
                component.componentOperationId().value());
        InventoryMutationJournalRecord existingRecord = new InventoryMutationJournalRecord(
                opId,
                "REWARD_DELIVERY",
                InventoryMutationJournalState.COMMITTED,
                1,
                "{}",
                Instant.now().plusSeconds(60),
                Instant.now(),
                Instant.now());

        when(journalPort.loadJournal(opId)).thenReturn(Optional.of(existingRecord));

        ItemRewardDeliveryHandler handler = new ItemRewardDeliveryHandler(sessionCoordinator, journalPort, nodeId);

        DeliveryResult result = handler.deliver(testGrant, component, recipientProfile);

        assertThat(result.success()).isTrue();
        assertThat(result.journalOperationId())
                .isEqualTo(component.componentOperationId().value());

        // Zero additional diamonds added! Remains exactly 5.
        assertThat(countItems(player, Material.DIAMOND)).isEqualTo(5);
        // recordIntent and commitMutation must not be called
        verify(journalPort, never())
                .recordIntent(any(), any(), any(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
        verify(journalPort, never()).commitMutation(any(), any(), any(), anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("Rollback on commit failure preserves unrelated Slot 12 contents completely untouched")
    void testUnrelatedSlot12PreservedOnRollback() {
        PlayerMock player = server.addPlayer();
        // Populate Slot 12 with emeralds
        ItemStack slot12Item = new ItemStack(Material.EMERALD, 5);
        player.getInventory().setItem(12, slot12Item);
        player.getInventory().setItem(0, new ItemStack(Material.IRON_INGOT, 10));

        when(sessionCoordinator.activeProfile(player.getUniqueId())).thenReturn(Optional.of(recipientProfile));
        PlayerSessionCoordinator.ActiveSession session = new PlayerSessionCoordinator.ActiveSession(
                new PlayerUuid(player.getUniqueId()), recipientProfile, 1L, 1L);
        when(sessionCoordinator.getActiveSession(player.getUniqueId())).thenReturn(session);

        when(journalPort.recordIntent(
                        any(), any(), any(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(InventoryMutationJournalOutcome.intentRecorded());
        when(journalPort.commitMutation(any(), any(), any(), anyLong(), anyLong(), any(), any()))
                .thenReturn(InventoryMutationJournalOutcome.conflict("DB write failure"));

        ItemRewardDeliveryHandler handler = new ItemRewardDeliveryHandler(sessionCoordinator, journalPort, nodeId);
        RewardGrantComponent component = new RewardGrantComponent(
                UUID.randomUUID(),
                testGrant.grantId(),
                0,
                new RewardComponentOperationId(UUID.randomUUID()),
                RewardComponentType.ITEM,
                "uxm:item_bundle",
                1,
                "{\"item\":\"DIAMOND\",\"amount\":32}",
                RewardComponentState.PENDING,
                null,
                Instant.now());

        DeliveryResult result = handler.deliver(testGrant, component, recipientProfile);

        assertThat(result.success()).isFalse();
        // Slot 12 MUST remain completely untouched!
        assertThat(player.getInventory().getItem(12)).isEqualTo(slot12Item);
        assertThat(player.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.IRON_INGOT, 10));
        assertThat(countItems(player, Material.DIAMOND)).isEqualTo(0);
    }

    @Test
    @DisplayName("Insufficient inventory space safely fails without ground drops and retains reward in inbox")
    void testInsufficientSpaceFailsSafelyWithoutDrops() {
        PlayerMock player = server.addPlayer();
        // Completely fill inventory
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            player.getInventory().setItem(i, new ItemStack(Material.COBBLESTONE, 64));
        }

        when(sessionCoordinator.activeProfile(player.getUniqueId())).thenReturn(Optional.of(recipientProfile));
        PlayerSessionCoordinator.ActiveSession session = new PlayerSessionCoordinator.ActiveSession(
                new PlayerUuid(player.getUniqueId()), recipientProfile, 1L, 1L);
        when(sessionCoordinator.getActiveSession(player.getUniqueId())).thenReturn(session);

        ItemRewardDeliveryHandler handler = new ItemRewardDeliveryHandler(sessionCoordinator, journalPort, nodeId);
        RewardGrantComponent component = new RewardGrantComponent(
                UUID.randomUUID(),
                testGrant.grantId(),
                0,
                new RewardComponentOperationId(UUID.randomUUID()),
                RewardComponentType.ITEM,
                "uxm:item_bundle",
                1,
                "{\"item\":\"DIAMOND\",\"amount\":10}",
                RewardComponentState.PENDING,
                null,
                Instant.now());

        DeliveryResult result = handler.deliver(testGrant, component, recipientProfile);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("Insufficient inventory space");
        // Zero dropped items in world
        assertThat(player.getWorld().getEntitiesByClass(Item.class)).isEmpty();
        // Zero mutations recorded in journal
        verify(journalPort, never())
                .recordIntent(any(), any(), any(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    private int countItems(PlayerMock player, Material mat) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == mat) {
                count += item.getAmount();
            }
        }
        return count;
    }
}

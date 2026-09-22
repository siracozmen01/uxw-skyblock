package com.uxplima.uxmskyblock.bukkit.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.vault.VaultActionType;
import com.uxplima.uxmskyblock.core.domain.vault.VaultAuditLogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.ArgumentCaptor;

/**
 * The vault says who moved what.
 *
 * <p>The audit table, the port call and the service method have been here since the vault work, and
 * the window passed an empty list at every single commit, so not one row was ever written. A chest
 * every island member can reach is exactly the thing an owner needs a record of, and the operator
 * has been setting an audit-log-limit that governed nothing.
 */
class TheVaultRecordsWhoMovedWhatTest {

    private ServerMock server;
    private IslandVaultService vaultService;
    private IslandVaultWindow window;
    private ProfileId actor;
    private IslandId islandId;

    private static SchedulerPort inlineScheduler() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        return scheduler;
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        vaultService = mock(IslandVaultService.class);
        actor = new ProfileId(UUID.randomUUID());
        islandId = IslandId.of(UUID.randomUUID());
        window = new IslandVaultWindow(
                vaultService,
                mock(IslandStoragePort.class),
                inlineScheduler(),
                VaultConfiguration.defaultConfiguration(),
                Messages.bundled(),
                null);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("Adding to an empty slot is recorded as a deposit of what was added")
    void addingIsADeposit() {
        List<VaultAuditLogEntry> trail =
                commit(List.of(air(), air()), new ItemStack[] {new ItemStack(Material.DIAMOND, 12), null});

        assertThat(trail)
                .extracting(
                        VaultAuditLogEntry::slot,
                        VaultAuditLogEntry::actionType,
                        VaultAuditLogEntry::itemSummary,
                        VaultAuditLogEntry::quantity)
                .containsExactly(tuple(0, VaultActionType.DEPOSIT, "DIAMOND", 12));
        assertThat(trail.get(0).actorProfileId()).isEqualTo(actor.toString());
        assertThat(trail.get(0).islandId()).isEqualTo(islandId);
        assertThat(trail.get(0).page()).isEqualTo(1);
    }

    @Test
    @DisplayName("Emptying a slot is recorded as a withdrawal of what was there")
    void emptyingIsAWithdrawal() {
        List<VaultAuditLogEntry> trail = commit(List.of(new ItemStack(Material.GOLD_INGOT, 7)), new ItemStack[] {null});

        assertThat(trail)
                .extracting(
                        VaultAuditLogEntry::slot,
                        VaultAuditLogEntry::actionType,
                        VaultAuditLogEntry::itemSummary,
                        VaultAuditLogEntry::quantity)
                .containsExactly(tuple(0, VaultActionType.WITHDRAW, "GOLD_INGOT", 7));
    }

    @Test
    @DisplayName("A stack that grew is recorded once, for what was added")
    void onlyWhatWasAddedIsRecorded() {
        List<VaultAuditLogEntry> grew = commit(
                List.of(new ItemStack(Material.COBBLESTONE, 16)),
                new ItemStack[] {new ItemStack(Material.COBBLESTONE, 40)});

        assertThat(grew)
                .extracting(VaultAuditLogEntry::actionType, VaultAuditLogEntry::quantity)
                .describedAs("twenty four went in, not forty")
                .containsExactly(tuple(VaultActionType.DEPOSIT, 24));
    }

    @Test
    @DisplayName("A stack that shrank is recorded once, for what was taken")
    void onlyWhatWasTakenIsRecorded() {
        List<VaultAuditLogEntry> shrank = commit(
                List.of(new ItemStack(Material.COBBLESTONE, 40)),
                new ItemStack[] {new ItemStack(Material.COBBLESTONE, 16)});

        assertThat(shrank)
                .extracting(VaultAuditLogEntry::actionType, VaultAuditLogEntry::quantity)
                .describedAs("twenty four came out, not sixteen")
                .containsExactly(tuple(VaultActionType.WITHDRAW, 24));
    }

    @Test
    @DisplayName("A slot whose item was swapped is recorded as both the taking and the putting")
    void aSwapIsTwoEntries() {
        List<VaultAuditLogEntry> trail = commit(
                List.of(new ItemStack(Material.IRON_INGOT, 3)), new ItemStack[] {new ItemStack(Material.EMERALD, 5)});

        assertThat(trail)
                .extracting(
                        VaultAuditLogEntry::actionType, VaultAuditLogEntry::itemSummary, VaultAuditLogEntry::quantity)
                .containsExactly(
                        tuple(VaultActionType.WITHDRAW, "IRON_INGOT", 3), tuple(VaultActionType.DEPOSIT, "EMERALD", 5));
    }

    @Test
    @DisplayName("A window nobody touched writes nothing")
    void anUntouchedWindowWritesNothing() {
        List<VaultAuditLogEntry> trail = commit(
                List.of(new ItemStack(Material.DIAMOND, 5), air()),
                new ItemStack[] {new ItemStack(Material.DIAMOND, 5), null});

        assertThat(trail)
                .describedAs("opening a chest and closing it is not an event")
                .isEmpty();
    }

    @Test
    @DisplayName("Every slot that moved is recorded, not just the first")
    void everySlotIsRecorded() {
        List<VaultAuditLogEntry> trail = commit(
                List.of(air(), new ItemStack(Material.STONE, 64), air()),
                new ItemStack[] {new ItemStack(Material.DIAMOND, 1), null, new ItemStack(Material.OAK_LOG, 9)});

        assertThat(trail)
                .extracting(VaultAuditLogEntry::slot, VaultAuditLogEntry::actionType)
                .containsExactly(
                        tuple(0, VaultActionType.DEPOSIT),
                        tuple(1, VaultActionType.WITHDRAW),
                        tuple(2, VaultActionType.DEPOSIT));
    }

    private static ItemStack air() {
        return new ItemStack(Material.AIR);
    }

    @SuppressWarnings("unchecked")
    private List<VaultAuditLogEntry> commit(List<ItemStack> openedWith, ItemStack[] atClose) {
        PlayerMock player = server.addPlayer();
        IslandVaultWindow.VaultHolder holder = new IslandVaultWindow.VaultHolder(
                islandId, 1, actor, UUID.randomUUID().toString(), openedWith, true, true);

        window.commit(player, holder, atClose);

        ArgumentCaptor<List<VaultAuditLogEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(vaultService).commitVaultPage(any(), any(), any(), any(), any(), captor.capture());
        return captor.getValue();
    }
}

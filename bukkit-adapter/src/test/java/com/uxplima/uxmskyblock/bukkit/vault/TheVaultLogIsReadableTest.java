package com.uxplima.uxmskyblock.bukkit.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.vault.VaultActionType;
import com.uxplima.uxmskyblock.core.domain.vault.VaultAuditLogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The vault log can be read by the people it is for.
 *
 * <p>The entries were written and the call that returns them had no caller anywhere, so a record
 * nobody could look at. Reading it is island management, not membership: every member can open the
 * chest, and who took what out of it is the owner's question.
 */
class TheVaultLogIsReadableTest {

    private ServerMock server;
    private PlayerMock player;
    private IslandVaultService vaultService;
    private IslandStoragePort islandStoragePort;
    private PlayerSessionCoordinator sessions;
    private IslandVaultWindow window;

    private ProfileId profileId;
    private IslandId islandId;

    /** Holds the off-thread work instead of running it, so the thread can be pinned. */
    private final List<Runnable> deferred = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        profileId = new ProfileId(UUID.randomUUID());
        islandId = IslandId.of(UUID.randomUUID());

        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(invocation -> deferred.add(invocation.getArgument(0, Runnable.class)))
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));

        vaultService = mock(IslandVaultService.class);
        islandStoragePort = mock(IslandStoragePort.class);
        sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(profileId));

        window = new IslandVaultWindow(
                vaultService,
                islandStoragePort,
                scheduler,
                VaultConfiguration.defaultConfiguration(),
                Messages.bundled(),
                sessions);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void islandWhereTheCallerIs(IslandRole role) {
        Island island = Island.create(
                islandId,
                IslandBounds.fromCenterAndRadius(0, 0, 100),
                new PlayerUuid(player.getUniqueId()),
                profileId,
                Instant.now());
        if (role != IslandRole.OWNER) {
            island = island.addMember(new com.uxplima.uxmskyblock.core.domain.island.IslandMember(
                    new PlayerUuid(player.getUniqueId()), profileId, role, Instant.now()));
        }
        when(islandStoragePort.findIslandIdByProfileId(profileId)).thenReturn(Optional.of(islandId));
        when(islandStoragePort.findIslandById(islandId)).thenReturn(Optional.of(island));
    }

    private VaultAuditLogEntry entry(String summary, int quantity, VaultActionType action) {
        return new VaultAuditLogEntry(
                UUID.randomUUID(), islandId, 1, profileId.toString(), action, 3, summary, quantity, Instant.now());
    }

    @Test
    @DisplayName("The read never happens on the thread the command arrives on")
    void theLogIsReadOffTheCommandThread() {
        islandWhereTheCallerIs(IslandRole.OWNER);
        when(vaultService.getRecentAuditLogs(any(), anyInt())).thenReturn(List.of());

        window.showLog(player, 10);

        verify(islandStoragePort, never()).findIslandIdByProfileId(any());
        verify(vaultService, never()).getRecentAuditLogs(any(), anyInt());
        assertThat(deferred).describedAs("work handed to the scheduler").hasSize(1);

        deferred.get(0).run();
        verify(vaultService).getRecentAuditLogs(islandId, 10);
    }

    @Test
    @DisplayName("The owner gets a header and one line for every entry")
    void theOwnerSeesEveryEntry() {
        islandWhereTheCallerIs(IslandRole.OWNER);
        when(vaultService.getRecentAuditLogs(any(), anyInt()))
                .thenReturn(List.of(
                        entry("DIAMOND", 12, VaultActionType.WITHDRAW),
                        entry("COBBLESTONE", 64, VaultActionType.DEPOSIT)));

        window.showLog(player, 10);
        deferred.get(0).run();

        assertThat(player.nextMessage()).describedAs("the header").isNotNull();
        assertThat(player.nextMessage()).describedAs("the first entry").contains("12");
        assertThat(player.nextMessage()).describedAs("the second entry").contains("64");
        assertThat(player.nextMessage())
                .describedAs("nothing after the last one")
                .isNull();
    }

    @Test
    @DisplayName("A member who may not manage the island is refused and the log is never read")
    void anOrdinaryMemberIsRefused() {
        islandWhereTheCallerIs(IslandRole.MEMBER);

        window.showLog(player, 10);
        deferred.get(0).run();

        verify(vaultService, never()).getRecentAuditLogs(any(), anyInt());
        assertThat(player.nextMessage()).describedAs("the refusal").isNotNull();
        assertThat(player.nextMessage()).describedAs("and no entries after it").isNull();
    }

    @Test
    @DisplayName("A co-owner may read it, because managing the island is the bar")
    void aCoOwnerMayRead() {
        islandWhereTheCallerIs(IslandRole.CO_OWNER);
        when(vaultService.getRecentAuditLogs(any(), anyInt()))
                .thenReturn(List.of(entry("EMERALD", 5, VaultActionType.DEPOSIT)));

        window.showLog(player, 10);
        deferred.get(0).run();

        verify(vaultService).getRecentAuditLogs(islandId, 10);
        assertThat(player.nextMessage()).describedAs("the header").isNotNull();
        assertThat(player.nextMessage()).describedAs("the entry").contains("5");
    }

    @Test
    @DisplayName("An empty log says it is empty rather than printing a header over nothing")
    void anEmptyLogSaysSo() {
        islandWhereTheCallerIs(IslandRole.OWNER);
        when(vaultService.getRecentAuditLogs(any(), anyInt())).thenReturn(List.of());

        window.showLog(player, 10);
        deferred.get(0).run();

        assertThat(player.nextMessage()).describedAs("the empty line").isNotNull();
        assertThat(player.nextMessage()).describedAs("and nothing else").isNull();
    }

    @Test
    @DisplayName("A player with no island is told so, not shown an empty log")
    void noIslandIsAnAnswer() {
        when(islandStoragePort.findIslandIdByProfileId(profileId)).thenReturn(Optional.empty());

        window.showLog(player, 10);
        deferred.get(0).run();

        verify(vaultService, never()).getRecentAuditLogs(any(), anyInt());
        assertThat(player.nextMessage()).isNotNull();
    }
}

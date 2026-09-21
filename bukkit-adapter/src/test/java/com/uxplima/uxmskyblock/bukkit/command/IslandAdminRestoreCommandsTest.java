package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.backup.BackupCatalogPort;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.snapshot.IslandRestoreService;
import com.uxplima.uxmskyblock.core.domain.backup.BackupCatalogRecord;
import com.uxplima.uxmskyblock.core.domain.backup.BackupLifecycleState;
import com.uxplima.uxmskyblock.core.domain.backup.BackupManifest;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.backup.BackupType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.snapshot.RestoreMode;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is admin restore}, {@code /is admin rollback} and {@code /is admin delete}, end to end.
 *
 * <p>These three write over a world or erase one, and they had no test. Three things are worth
 * pinning. The bucket is the operator's, not one written into the source. The rollback picks the
 * newest backup that actually finished, because a half written one restores nothing. And the mode a
 * caller names is the mode the service is given, because the difference between them is whether the
 * island's membership is overwritten.
 */
class IslandAdminRestoreCommandsTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId ADMIN_PROFILE = new ProfileId(UUID.randomUUID());
    private static final StorageBucket CONFIGURED_BUCKET = new StorageBucket("the-operators-own-bucket");

    private ServerMock server;
    private PlayerMock admin;
    private PlayerMock ordinary;
    private IslandRestoreService restore;
    private BackupService backups;
    private BackupCatalogPort catalog;
    private IslandRecycleService recycle;
    private CommandDispatcher<CommandSourceStack> dispatcher;

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
        doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onGlobal(any(Runnable.class));
        return scheduler;
    }

    private static BackupCatalogRecord record(BackupSetId id, BackupLifecycleState state, Instant createdAt) {
        return new BackupCatalogRecord(
                id,
                BackupType.ROOT_BACKUP,
                "ISLAND",
                ISLAND.value().toString(),
                state,
                1L,
                1L,
                1,
                "1.0.0",
                null,
                createdAt,
                createdAt,
                createdAt);
    }

    private static BackupManifest manifest(BackupSetId id) {
        return new BackupManifest(
                id,
                BackupType.ROOT_BACKUP,
                "ISLAND",
                ISLAND.value().toString(),
                Instant.now(),
                1L,
                1L,
                1,
                "1.0.0",
                Map.of(),
                "CONSISTENT");
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        admin = server.addPlayer("Admin");
        ordinary = server.addPlayer("Ordinary");
        admin.addAttachment(MockBukkit.createMockPlugin(), CatalogPermissions.ADMIN_MANAGE.node(), true);

        catalog = mock(BackupCatalogPort.class);
        when(catalog.findByRoot(anyString(), anyString())).thenReturn(List.of());
        when(catalog.findById(any())).thenReturn(Optional.empty());

        restore = mock(IslandRestoreService.class);
        when(restore.catalogPort()).thenReturn(catalog);
        when(restore.executeRestore(any(), any(), anyString(), anyBoolean(), any()))
                .thenAnswer(invocation -> new IslandRestoreService.RestoreOutcome.Success(BackupSetId.random(), 3));

        backups = mock(BackupService.class);
        when(backups.loadManifest(any(), anyString())).thenReturn(Optional.empty());

        recycle = mock(IslandRecycleService.class);
        when(recycle.executeReset(any(), any(), any(), anyBoolean()))
                .thenReturn(
                        CompletableFuture.completedFuture(new IslandRecycleService.RecycleResult.Failure("no world")));

        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findOwnerProfileId(ISLAND)).thenReturn(Optional.empty());

        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(admin.getUniqueId())).thenReturn(Optional.of(ADMIN_PROFILE));

        IslandAdminRestoreCommands commands = new IslandAdminRestoreCommands(
                () -> restore,
                () -> backups,
                () -> recycle,
                () -> CONFIGURED_BUCKET,
                locations,
                sessions,
                inlineScheduler(),
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()));

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildRestore());
        dispatcher.register(commands.buildRollback());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void run(String line, CommandSender sender) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        dispatcher.execute(line, source);
    }

    @Test
    @DisplayName("The manifest is looked for in the bucket the operator named, not one written in the source")
    void theBucketIsTheOperatorsOwn() throws Exception {
        BackupSetId id = BackupSetId.random();

        run("restore " + id.value(), admin);

        verify(backups).loadManifest(eq(CONFIGURED_BUCKET), anyString());
    }

    @Test
    @DisplayName("A restore with no mode named takes the one that changes least")
    void aRestoreWithNoModeTakesTheSafeOne() throws Exception {
        BackupSetId id = BackupSetId.random();
        when(backups.loadManifest(any(), anyString())).thenReturn(Optional.of(manifest(id)));

        run("restore " + id.value(), admin);

        verify(restore)
                .executeRestore(any(), eq(CONFIGURED_BUCKET), anyString(), eq(true), eq(RestoreMode.safeDefault()));
    }

    @Test
    @DisplayName("Every mode the plugin publishes is a word the command accepts and passes through")
    void everyPublishedModeIsCarried() throws Exception {
        BackupSetId id = BackupSetId.random();
        when(backups.loadManifest(any(), anyString())).thenReturn(Optional.of(manifest(id)));

        for (RestoreMode mode : RestoreMode.values()) {
            run("restore " + id.value() + " " + mode.name(), admin);
        }

        for (RestoreMode mode : RestoreMode.values()) {
            verify(restore).executeRestore(any(), any(), anyString(), anyBoolean(), eq(mode));
        }
    }

    @Test
    @DisplayName("A mode nobody publishes restores nothing rather than falling back to one")
    void anUnknownModeRestoresNothing() throws Exception {
        BackupSetId id = BackupSetId.random();
        when(backups.loadManifest(any(), anyString())).thenReturn(Optional.of(manifest(id)));

        run("restore " + id.value() + " EVERYTHING", admin);

        verify(restore, never()).executeRestore(any(), any(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("A backup with no manifest anywhere restores nothing")
    void aMissingManifestRestoresNothing() throws Exception {
        run("restore " + BackupSetId.random().value(), admin);

        verify(restore, never()).executeRestore(any(), any(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("A rollback picks the newest backup that finished, not the newest one started")
    void rollbackPicksTheNewestFinishedBackup() throws Exception {
        Instant now = Instant.now();
        BackupSetId finished = BackupSetId.random();
        BackupSetId halfWritten = BackupSetId.random();
        when(catalog.findByRoot("ISLAND", ISLAND.value().toString()))
                .thenReturn(List.of(
                        record(finished, BackupLifecycleState.AVAILABLE, now.minus(2, ChronoUnit.HOURS)),
                        record(halfWritten, BackupLifecycleState.UPLOADING, now)));
        when(backups.loadManifest(any(), anyString())).thenReturn(Optional.of(manifest(finished)));

        run("rollback " + ISLAND.value(), admin);

        verify(backups).loadManifest(eq(CONFIGURED_BUCKET), eq("backups/" + finished));
    }

    @Test
    @DisplayName("A rollback of an island with no finished backup restores nothing")
    void rollbackWithNoBackupRestoresNothing() throws Exception {
        when(catalog.findByRoot("ISLAND", ISLAND.value().toString()))
                .thenReturn(List.of(record(BackupSetId.random(), BackupLifecycleState.FAILED, Instant.now())));

        run("rollback " + ISLAND.value(), admin);

        verify(restore, never()).executeRestore(any(), any(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("A rollback names the island's own backups, not every backup on the server")
    void rollbackAsksForThisIslandsBackups() throws Exception {
        run("rollback " + ISLAND.value(), admin);

        verify(catalog).findByRoot("ISLAND", ISLAND.value().toString());
    }

    @Test
    @DisplayName("A player without an admin permission cannot reach either branch")
    void anOrdinaryPlayerCannotReachEitherBranch() {
        for (String line : new String[] {"restore " + BackupSetId.random().value(), "rollback " + ISLAND.value()}) {
            assertThatThrownBy(() -> run(line, ordinary))
                    .describedAs("%s must be out of reach without a permission", line)
                    .isInstanceOf(Exception.class);
        }
        verify(restore, never()).executeRestore(any(), any(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("A restore with no backup id at all is refused by the parser")
    void restoreNeedsABackupId() {
        assertThatThrownBy(() -> run("restore", admin)).isInstanceOf(Exception.class);
        verify(restore, never()).executeRestore(any(), any(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("A backup id that is not an identifier at all is an answer, not a stack trace")
    void aMalformedBackupIdIsAnAnswer() throws Exception {
        run("restore notauuid", admin);

        assertThat(admin.nextMessage()).isNotNull();
        verify(restore, never()).executeRestore(any(), any(), anyString(), anyBoolean(), any());
    }
}

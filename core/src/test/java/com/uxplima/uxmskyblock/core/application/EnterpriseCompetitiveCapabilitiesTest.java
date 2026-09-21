package com.uxplima.uxmskyblock.core.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedStoragePort;
import com.uxplima.uxmskyblock.core.application.backup.BackupCatalogPort;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.home.HomeService;
import com.uxplima.uxmskyblock.core.application.home.HomeStoragePort;
import com.uxplima.uxmskyblock.core.application.network.PlacementStrategy;
import com.uxplima.uxmskyblock.core.application.notification.NotificationService;
import com.uxplima.uxmskyblock.core.application.notification.NotificationStoragePort;
import com.uxplima.uxmskyblock.core.application.snapshot.IslandRestoreService;
import com.uxplima.uxmskyblock.core.application.snapshot.RootRelationalSnapshotPort;
import com.uxplima.uxmskyblock.core.application.snapshot.WorldDimensionSnapshotPort;
import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEvent;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityVisibility;
import com.uxplima.uxmskyblock.core.domain.backup.BackupArtifact;
import com.uxplima.uxmskyblock.core.domain.backup.BackupManifest;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.backup.BackupType;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.home.Home;
import com.uxplima.uxmskyblock.core.domain.home.HomeLimitPolicy;
import com.uxplima.uxmskyblock.core.domain.home.HomeScope;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.network.NodeHealth;
import com.uxplima.uxmskyblock.core.domain.notification.Notification;
import com.uxplima.uxmskyblock.core.domain.notification.NotificationCategory;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.core.domain.template.CreationAction;
import com.uxplima.uxmskyblock.core.domain.template.StartTemplateBundle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EnterpriseCompetitiveCapabilitiesTest {

    @Test
    @DisplayName("DimensionId normalizes and matches canonical dimensions")
    void dimensionIdNormalizes() {
        DimensionId dim = DimensionId.of("OVERWORLD");
        assertThat(dim.value()).isEqualTo("overworld");
        assertThat(dim).isEqualTo(DimensionId.OVERWORLD);
        assertThat(DimensionId.THE_NETHER.value()).isEqualTo("the_nether");
        assertThat(DimensionId.THE_END.value()).isEqualTo("the_end");
    }

    @Test
    @DisplayName("StartTemplateBundle resolves assets and actions across dimensions")
    void startTemplateBundleResolves() {
        CreationAction paste =
                new CreationAction.PasteSchematic(DimensionId.OVERWORLD, "templates/default.schem", 0, 64, 0);
        CreationAction spawn = new CreationAction.SetSpawn(DimensionId.OVERWORLD, 0.5, 65.0, 0.5, 0f, 0f);

        StartTemplateBundle bundle = new StartTemplateBundle(
                "classic",
                "Classic Skyblock",
                "Original island experience",
                "skyblock.template.classic",
                Map.of(DimensionId.OVERWORLD, "templates/default.schem"),
                List.of(paste, spawn));

        assertThat(bundle.assetPathFor(DimensionId.OVERWORLD)).contains("templates/default.schem");
        assertThat(bundle.assetPathFor(DimensionId.THE_NETHER)).isEmpty();
        assertThat(bundle.creationActions()).hasSize(2);
    }

    @Test
    @DisplayName("PlacementStrategy heuristics allocate without advancing authority")
    void placementStrategyAllocates() {
        ServerNodeId nodeA = new ServerNodeId("node-a");
        ServerNodeId nodeB = new ServerNodeId("node-b");
        PrimaryGameplayRootRef root = new PrimaryGameplayRootRef(
                com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId.fromString("inst-123"),
                "island-123",
                "ISLAND",
                Instant.now());

        List<NodeHealth> nodes =
                List.of(new NodeHealth(nodeA, true, 10, 100, 20.0), new NodeHealth(nodeB, true, 50, 100, 15.0));

        // Least loaded picks nodeA (10/100 vs 50/100)
        PlacementStrategy leastLoaded = PlacementStrategy.leastLoaded();
        assertThat(leastLoaded.selectNode(root, nodes)).contains(nodeA);

        // MSPT aware picks nodeB (15.0ms vs 20.0ms)
        PlacementStrategy msptAware = PlacementStrategy.msptAware();
        assertThat(msptAware.selectNode(root, nodes)).contains(nodeB);

        // Capacity weighted picks nodeA (90 available vs 50 available)
        PlacementStrategy capWeighted = PlacementStrategy.capacityWeighted();
        assertThat(capWeighted.selectNode(root, nodes)).contains(nodeA);

        // Round robin alternates
        PlacementStrategy rr = PlacementStrategy.roundRobin();
        ServerNodeId first = rr.selectNode(root, nodes).orElseThrow();
        ServerNodeId second = rr.selectNode(root, nodes).orElseThrow();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("HomeService enforces limit policy and persists homes")
    void homeServiceEnforcesLimits() {
        Map<String, Home> storage = new HashMap<>();
        HomeStoragePort port = new HomeStoragePort() {
            @Override
            public void saveHome(Home home) {
                storage.put(home.name(), home);
            }

            @Override
            public Optional<Home> findHome(ProfileId profileId, String name) {
                return Optional.ofNullable(storage.get(name));
            }

            @Override
            public List<Home> findHomesByProfileId(ProfileId profileId) {
                return new ArrayList<>(storage.values());
            }

            @Override
            public List<Home> findHomesByIslandId(IslandId islandId) {
                return new ArrayList<>(storage.values());
            }

            @Override
            public boolean deleteHome(ProfileId profileId, String name) {
                return storage.remove(name) != null;
            }

            @Override
            public int countHomes(ProfileId profileId) {
                return storage.size();
            }
        };

        HomeLimitPolicy policy = tier -> 2; // Strict limit of 2
        HomeService service = new HomeService(port, policy);

        ProfileId profileId = new ProfileId(UUID.randomUUID());
        IslandId islandId = new IslandId(UUID.randomUUID());

        var res1 =
                service.setHome(profileId, islandId, "main", HomeScope.PERSONAL, "skyblock_world", 0, 70, 0, 0, 0, 0);
        assertThat(res1).isInstanceOf(HomeService.SetHomeResult.Success.class);

        var res2 =
                service.setHome(profileId, islandId, "farm", HomeScope.PERSONAL, "skyblock_world", 10, 70, 10, 0, 0, 0);
        assertThat(res2).isInstanceOf(HomeService.SetHomeResult.Success.class);

        // Third home exceeds limit of 2
        var res3 =
                service.setHome(profileId, islandId, "shop", HomeScope.PERSONAL, "skyblock_world", 20, 70, 20, 0, 0, 0);
        assertThat(res3).isInstanceOf(HomeService.SetHomeResult.LimitExceeded.class);

        assertThat(service.listHomes(profileId)).hasSize(2);
        assertThat(service.deleteHome(profileId, "farm")).isTrue();
        assertThat(service.listHomes(profileId)).hasSize(1);
    }

    @Test
    @DisplayName("ActivityFeedService appends and queries user-facing events")
    void activityFeedRecords() {
        List<ActivityEvent> events = new ArrayList<>();
        ActivityFeedStoragePort port = new ActivityFeedStoragePort() {
            @Override
            public void appendEvent(ActivityEvent event) {
                events.add(event);
            }

            @Override
            public List<ActivityEvent> findEventsByInstanceId(String instanceId, int limit) {
                return events.stream()
                        .filter(e -> e.instanceId().equals(instanceId))
                        .limit(limit)
                        .toList();
            }
        };

        ActivityFeedService service = new ActivityFeedService(port);
        service.recordActivity(
                "inst-1",
                new ProfileId(UUID.randomUUID()),
                ActivityEventType.BANK_DEPOSIT,
                ActivityVisibility.MEMBERS_ONLY,
                "uxm:bank_deposit",
                1,
                "{\"amount\": 5000}");

        List<ActivityEvent> result = service.getRecentActivities("inst-1", 10);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).eventType()).isEqualTo(ActivityEventType.BANK_DEPOSIT);
    }

    @Test
    @DisplayName("NotificationService queues and drains offline notifications")
    void notificationServiceDrains() {
        List<Notification> store = new ArrayList<>();
        NotificationStoragePort port = new NotificationStoragePort() {
            @Override
            public void saveNotification(Notification notification) {
                store.add(notification);
            }

            @Override
            public List<Notification> findPendingNotifications(ProfileId recipientProfileId) {
                return store.stream().filter(n -> !n.isRead()).toList();
            }

            @Override
            public void markAsRead(UUID notificationId, Instant readAt) {}

            @Override
            public void markAllAsRead(ProfileId recipientProfileId, Instant readAt) {
                for (int i = 0; i < store.size(); i++) {
                    store.set(i, store.get(i).markAsRead(readAt));
                }
            }

            @Override
            public int countUnread(ProfileId recipientProfileId) {
                return (int) store.stream().filter(n -> !n.isRead()).count();
            }
        };

        NotificationService service = new NotificationService(port);
        ProfileId recipient = new ProfileId(UUID.randomUUID());

        service.dispatchNotification(
                recipient, NotificationCategory.INVITE, "uxm:island_invite", 1, "{\"inviter\":\"Leader\"}", null);

        assertThat(service.getUnreadCount(recipient)).isEqualTo(1);
        List<Notification> drained = service.drainPendingNotifications(recipient);
        assertThat(drained).hasSize(1);
        assertThat(service.getUnreadCount(recipient)).isEqualTo(0);
    }

    @Test
    @DisplayName("IslandRestoreService restores verified backups and fails closed on corrupted artifacts")
    void islandRestoreServiceFailsClosedOnChecksumMismatch() {
        BackupCatalogPort mockCatalog = mock(BackupCatalogPort.class);
        ObjectStoragePort mockStorage = mock(ObjectStoragePort.class);
        RootRelationalSnapshotPort mockRelational = mock(RootRelationalSnapshotPort.class);
        WorldDimensionSnapshotPort mockWorld = mock(WorldDimensionSnapshotPort.class);

        IslandRestoreService restoreService =
                new IslandRestoreService(mockCatalog, mockStorage, mockRelational, mockWorld);

        StorageBucket bucket = new StorageBucket("backups");
        String prefix = "islands/test-island";

        byte[] payload = "relational-data-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String correctSha = BackupService.computeSha256(payload);

        BackupManifest manifest = new BackupManifest(
                BackupSetId.random(),
                BackupType.ROOT_BACKUP,
                "ISLAND",
                "test-island",
                Instant.now(),
                1L,
                1L,
                1,
                "1.0",
                Map.of("relational.json", new BackupArtifact("relational.json", payload.length, correctSha)),
                "FULL_RESTORE_CONSISTENT");

        // 1. Missing marker fails closed
        when(mockStorage.exists(eq(bucket), eq("islands/test-island/AVAILABLE.marker")))
                .thenReturn(false);
        var result1 = restoreService.executeRestore(manifest, bucket, prefix, false);
        assertThat(result1).isInstanceOf(IslandRestoreService.RestoreOutcome.Failure.class);

        // 2. Corrupted data fails closed
        when(mockStorage.exists(eq(bucket), eq("islands/test-island/AVAILABLE.marker")))
                .thenReturn(true);
        byte[] corruptedPayload = "corrupted-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(mockStorage.getObject(eq(bucket), eq("islands/test-island/relational.json")))
                .thenReturn(Optional.of(corruptedPayload));

        var result2 = restoreService.executeRestore(manifest, bucket, prefix, false);
        assertThat(result2).isInstanceOf(IslandRestoreService.RestoreOutcome.Failure.class);
        verify(mockRelational, never()).restoreRelationalSnapshot(any(), any(), any());

        // 3. Valid checksum succeeds
        when(mockStorage.getObject(eq(bucket), eq("islands/test-island/relational.json")))
                .thenReturn(Optional.of(payload));

        var result3 = restoreService.executeRestore(manifest, bucket, prefix, false);
        assertThat(result3).isInstanceOf(IslandRestoreService.RestoreOutcome.Success.class);
        verify(mockRelational).restoreRelationalSnapshot(any(), eq(payload), any());
    }
}

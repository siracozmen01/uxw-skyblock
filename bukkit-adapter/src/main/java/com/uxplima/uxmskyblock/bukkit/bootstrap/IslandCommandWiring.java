package com.uxplima.uxmskyblock.bukkit.bootstrap;

import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmskyblock.bukkit.command.IslandCommandTree;
import com.uxplima.uxmskyblock.bukkit.command.IslandFeatures;
import com.uxplima.uxmskyblock.bukkit.health.SkyblockHealth;
import com.uxplima.uxmskyblock.bukkit.world.IslandWorldCheck;
import com.uxplima.uxmskyblock.core.application.flag.IslandFlagService;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/**
 * The {@code /is} command tree and every service its branches reach.
 *
 * <p>Built once the integrations it hands to its branches exist: the menus, the placeholders, the
 * economy bridge and the router.
 */
final class IslandCommandWiring {

    private IslandCommandWiring() {}

    static IslandCommandTree build(
            IntegrationWiring integration,
            JavaPlugin plugin,
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            GameplayWiring gameplay,
            Housekeeping housekeeping) {
        ServerNodeId serverNodeId = config.nodeConfig().nodeId();
        String worldName = config.nodeConfig().worldName();
        IslandCommandTree tree = new IslandCommandTree(
                gameplay.createIslandUseCase(),
                gameplay.locationService(),
                new IslandFlagService(persistence.islandStoragePort(), persistence.islandMutationLock()),
                gameplay.bankService(),
                persistence.islandUpgradeStoragePort(),
                gameplay.leaderboardService(),
                gameplay.biomeAdapter(),
                gameplay.presetCatalog(),
                gameplay.schematicEngine(),
                gameplay.protectionListener(),
                authority.sessionCoordinator(),
                gameplay.scheduler(),
                integration.messages(),
                config.homeConfig(),
                serverNodeId,
                worldName,
                integration.economyBridge(),
                IslandFeatures.builder()
                        .controlMenu(integration.controlMenu())
                        .chatService(gameplay.chatService())
                        .inactivityService(gameplay.inactivityService())
                        .freezeService(gameplay.freezeService())
                        .missionsMenu(gameplay.missionsMenu())
                        .boundaryService(gameplay.boundaryService())
                        .recycleService(gameplay.recycleService())
                        .resetMenu(gameplay.resetConfirmationMenu())
                        .worthService(gameplay.worthService())
                        .dimensionListener(gameplay.dimensionListener())
                        .limitService(gameplay.limitService())
                        .antiAbuseService(gameplay.antiAbuseService())
                        .boosterService(gameplay.boosterService())
                        .boosterMenu(gameplay.boosterMenu())
                        .upgradeService(gameplay.upgradeService())
                        .shopService(gameplay.shopService())
                        .shopMenu(gameplay.shopMenu())
                        // The grant subsystem was complete underneath and nothing could make a
                        // grant: the whole write side had no caller, so the check on every click
                        // asked about grants that could not exist.
                        .temporaryAccessService(
                                config.moduleSettings().isModuleEnabled("temporary-access")
                                        ? gameplay.temporaryAccessService()
                                        : null)
                        .build());
        // A button runs an island command under the operator's names for it, so the verbs need the tree.
        SkyblockMenuVerbs.register(integration.menuEngine(), integration.messages(), tree::typed);
        tree.useTemporaryAccess(
                config.temporaryAccessConfig(), authority.nodeProcessIdentity(), authority.profileTypes());
        // The inbox, its table, its ten categories and the delivery on join were all here and
        // nothing ever wrote a row, so "while you were away" was always empty.
        tree.useNotifications(gameplay.notificationService());
        // Nothing ever wrote an activity event, so every island's feed was empty for as long as the
        // server ran.
        tree.useActivityFeed(gameplay.activityFeedService());
        housekeeping.tellTheFeedWhenAMissionFinishes(gameplay);
        tree.setBankruptcyService(gameplay.bankruptcyService());
        tree.setHomeService(gameplay.homeService());
        tree.setVaultWindow(gameplay.vaultWindow());
        tree.useLifecycle(gameplay.playerLifecycle());
        tree.useGameModes(gameplay.gameModeHierarchyService()::modeOf);
        tree.useLeaderboards(gameplay.leaderboardMetrics());
        // A OneBlock island's standing, as the operator's menu file draws it and as placeholders.
        var oneBlockPanel = gameplay.oneBlockWiring().panel(integration.menuEngine());
        tree.useOneBlock(oneBlockPanel);
        integration.placeholderExpansion().useOneBlock(oneBlockPanel);
        tree.setActivityFeedService(gameplay.activityFeedService());
        tree.setNameService(gameplay.islandNameService());
        tree.setNetworkRouter(integration.networkRouter());
        tree.setRestoreService(gameplay.islandRestoreService());
        tree.setBackupService(gameplay.backupService());
        tree.setBackupBucket(gameplay.backupBucket());
        tree.setIslandBackupService(gameplay.islandBackupService());
        tree.setDatabaseBackupService(gameplay.databaseBackupService());
        tree.setMembershipService(gameplay.membershipService());
        tree.setSeasonService(gameplay.seasonService());
        // A reload reads the catalogues and the menus and nothing else: no service is re-bound and
        // no table is touched, because hot swapping a subsystem is how a plugin leaks classloaders
        // and leaves listeners behind.
        tree.setReloader(
                new SkyblockReloader(integration.messages().provider(), config.dataDir(), integration.menuEngine()));
        String islandWorld = config.nodeConfig().worldName();
        java.util.List<com.uxplima.uxmlib.health.HealthCheck> checks = java.util.List.of(
                SkyblockHealth.storage(persistence::databaseAnswers),
                SkyblockHealth.windows(
                        () -> integration.menuEngine().loadedSpecs().size()),
                SkyblockHealth.placeholders(
                        () -> integration.placeholderExpansion().isPublished()),
                SkyblockHealth.islandWorld(
                        () -> IslandWorldCheck.warningFor(
                                islandWorld, plugin.getServer().getWorld(islandWorld), plugin.getName()),
                        () -> plugin.getServer().getWorld(islandWorld) != null),
                SkyblockHealth.economy(() -> integration.economyBridge().isEconomyAvailable()));
        tree.setHealthChecks(() -> checks);
        // Four subsystems that were running with no door. Every one of them had a service, a table
        // and a feature module, and no command a player could type.
        tree.setWarpService(gameplay.warpService());
        tree.setWarpBrowseMenu(gameplay.warpBrowseMenu());
        tree.setBiomeConfiguration(config.biomeConfig());
        tree.setRecalculationCooldown(config.levelConfig().recalculationCooldown());
        tree.setAntiAbuseConfiguration(config.antiAbuseConfig());
        tree.setInteractionEffects(config.effectsConfig());
        tree.setSocialService(gameplay.socialService());
        tree.setAllianceService(gameplay.allianceService());
        tree.setRewardInboxService(gameplay.rewardInboxService());
        tree.useMarkerSynchroniser(integration.markerSynchroniser());
        tree.setMissionService(gameplay.missionService());
        return tree;
    }
}

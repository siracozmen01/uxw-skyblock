package com.uxplima.uxmskyblock.bukkit.command;

/**
 * Builds the command groups the island tree is assembled from.
 *
 * <p>What the verbs are, and where they hang, are two questions. This answers the first: fourteen
 * groups, each constructed with what it needs and nothing else. {@link IslandCommandTree} answers
 * the second, and holding both put it back over the line the standards draw.
 *
 * <p>Every collaborator arrives through {@link IslandCommandTree}, including the ones it is handed
 * after construction, so a group built here sees whatever the tree was given by the time it is
 * asked to build. That is why this takes the tree rather than a list of its parts.
 */
final class CommandGroupBuilder {

    private final IslandCommandTree tree;

    CommandGroupBuilder(IslandCommandTree tree) {
        this.tree = java.util.Objects.requireNonNull(tree, "tree must not be null");
    }

    /**
     * The command groups this tree is made of.
     *
     * <p>Building nine of them and assembling the tree out of them were one method, which is how
     * that method reached the size the standards draw a line at. The two halves answer different
     * questions: what the verbs are, and where they hang.
     */
    record CommandGroups(
            IslandBankCommands bankCommands,
            IslandChatCommands chatCommands,
            IslandAdminCommands adminCommands,
            IslandLifecycleCommands lifecycleCommands,
            IslandNavigationCommands navigationCommands,
            IslandProgressionCommands progressionCommands,
            IslandMechanicsCommands mechanicsCommands,
            IslandActivityCommands activityCommands,
            IslandHomeCommands homeCommands,
            IslandWarpCommands warpCommands,
            IslandSocialCommands socialCommands,
            IslandAllianceCommands allianceCommands,
            IslandRewardCommands rewardCommands,
            IslandFlagCommands flagCommands,
            IslandVisitorCommands visitorCommands,
            IslandMembershipCommands membershipCommands,
            IslandInfoCommands infoCommands,
            IslandSeasonCommands seasonCommands,
            IslandReloadCommands reloadCommands,
            IslandUpgradeCommands upgradeCommands) {}

    CommandGroups build() {

        IslandBankCommands bankCommands = new IslandBankCommands(
                tree.islandBankService,
                tree.islandLocationService,
                tree.economyBridge,
                tree.schedulerPort,
                tree.serverNodeId,
                () -> tree.bankruptcyService,
                tree.messages,
                tree.sessionCoordinator);

        IslandUpgradeCommands upgradeCommands = new IslandUpgradeCommands(
                () -> tree.features.upgradeService(),
                tree.islandLocationService,
                tree.schedulerPort,
                tree.serverNodeId,
                tree.messages,
                tree.sessionCoordinator);

        IslandChatCommands chatCommands = new IslandChatCommands(
                () -> tree.features.chatService(), tree.schedulerPort, tree.messages, tree.sessionCoordinator);

        IslandAdminCommands adminCommands = new IslandAdminCommands(
                () -> tree.features.inactivityService(),
                () -> tree.features.freezeService(),
                () -> tree.restoreService,
                () -> tree.backupService,
                () -> tree.features.recycleService(),
                () -> tree.backupBucket,
                () -> tree.islandBackupService,
                tree.protectionListener,
                tree.islandLocationService,
                tree.sessionCoordinator,
                tree.schedulerPort,
                tree.worldName,
                tree.messages);

        IslandLifecycleCommands lifecycleCommands = new IslandLifecycleCommands(
                tree.createIslandUseCase,
                tree.islandLocationService,
                tree.presetCatalog,
                tree.schematicEngine,
                tree.protectionListener,
                tree.sessionCoordinator,
                tree.schedulerPort,
                tree.serverNodeId,
                tree.worldName,
                () -> tree.features.antiAbuseService(),
                () -> tree.features.recycleService(),
                () -> tree.features.resetMenu(),
                () -> tree.nameService,
                tree.messages);

        IslandNavigationCommands navigationCommands = new IslandNavigationCommands(
                tree.islandLocationService,
                tree.sessionCoordinator,
                tree.schedulerPort,
                tree.worldName,
                () -> tree.features.dimensionListener(),
                () -> tree.networkRouter,
                () -> tree.warpService,
                () -> tree.allianceService,
                tree.messages);

        IslandProgressionCommands progressionCommands = new IslandProgressionCommands(
                tree.islandLocationService,
                tree.islandBankService,
                tree.islandLeaderboardService,
                tree.biomeModificationPort,
                tree.sessionCoordinator,
                tree.schedulerPort,
                () -> tree.features.worthService(),
                () -> tree.missionService,
                tree.messages);

        IslandMechanicsCommands mechanicsCommands = new IslandMechanicsCommands(
                tree.islandLocationService,
                tree.sessionCoordinator,
                tree.schedulerPort,
                () -> tree.features.limitService(),
                () -> tree.features.antiAbuseService(),
                () -> tree.features.boosterService(),
                () -> tree.features.boosterMenu(),
                () -> tree.features.missionsMenu(),
                () -> tree.features.boundaryService(),
                tree.messages);

        lifecycleCommands.useMarkerSynchroniser(tree.markerSynchroniser);

        IslandActivityCommands activityCommands = new IslandActivityCommands(
                () -> tree.activityFeedService,
                tree.islandLocationService,
                tree.schedulerPort,
                tree.messages,
                tree.sessionCoordinator);

        IslandHomeCommands homeCommands = new IslandHomeCommands(
                () -> tree.homeService,
                tree.islandLocationService,
                tree.schedulerPort,
                tree.homeConfiguration,
                tree.messages,
                tree.sessionCoordinator);

        // Four subsystems were built, wired and running with no command to reach them: warps, the
        // social system, alliances and the reward inbox. Rewards were being issued into a table a
        // player had no way to open.
        IslandWarpCommands warpCommands = new IslandWarpCommands(
                () -> tree.warpService,
                tree.islandLocationService,
                tree.schedulerPort,
                tree.messages,
                tree.sessionCoordinator);

        IslandSocialCommands socialCommands = new IslandSocialCommands(
                () -> tree.socialService,
                tree.protectionListener.spatialIndex(),
                tree.schedulerPort,
                tree.messages,
                tree.sessionCoordinator);

        IslandAllianceCommands allianceCommands = new IslandAllianceCommands(
                () -> tree.allianceService,
                tree.islandLocationService,
                tree.schedulerPort,
                tree.messages,
                tree.sessionCoordinator);

        IslandRewardCommands rewardCommands = new IslandRewardCommands(
                () -> tree.rewardInboxService, tree.schedulerPort, tree.messages, tree.sessionCoordinator);

        // Sixteen island flags, read by the protection listener on every event, and nothing could
        // change one. PvP was on or off according to a default nobody could move.
        IslandFlagCommands flagCommands = new IslandFlagCommands(
                tree.flagService,
                tree.islandLocationService,
                tree.schedulerPort,
                tree.messages,
                tree.sessionCoordinator);

        // The ban list had a service, a port, a table and a reader, and no command could put a name
        // in it. The visit gate asked an empty list on every island on every server.
        IslandVisitorCommands visitorCommands = new IslandVisitorCommands(
                () -> tree.warpService,
                tree.flagService,
                tree.islandLocationService,
                tree.schedulerPort,
                tree.messages,
                tree.sessionCoordinator);

        // A skyblock with no way to make a team. The domain carried addMember and removeMember, the
        // roles carried the four member permissions, the MEMBERS upgrade raised a cap, and the
        // members menu had an invite button that sent a hint message.
        IslandMembershipCommands membershipCommands = new IslandMembershipCommands(
                () -> tree.membershipService,
                tree.islandLocationService,
                tree.schedulerPort,
                tree.messages,
                tree.sessionCoordinator);

        IslandInfoCommands infoCommands = new IslandInfoCommands(
                tree.islandLocationService,
                () -> tree.nameService,
                () -> tree.features.boosterService(),
                tree.schedulerPort,
                tree.messages,
                tree.sessionCoordinator);

        // The season service, its snapshots, its payouts and its storage were here since the season
        // work and no command reached any of it.
        IslandSeasonCommands seasonCommands =
                new IslandSeasonCommands(() -> tree.seasonService, tree.schedulerPort, tree.messages);

        IslandReloadCommands reloadCommands =
                new IslandReloadCommands(() -> tree.reloader, tree.schedulerPort, tree.messages);

        return new CommandGroups(
                bankCommands,
                chatCommands,
                adminCommands,
                lifecycleCommands,
                navigationCommands,
                progressionCommands,
                mechanicsCommands,
                activityCommands,
                homeCommands,
                warpCommands,
                socialCommands,
                allianceCommands,
                rewardCommands,
                flagCommands,
                visitorCommands,
                membershipCommands,
                infoCommands,
                seasonCommands,
                reloadCommands,
                upgradeCommands);
    }
}

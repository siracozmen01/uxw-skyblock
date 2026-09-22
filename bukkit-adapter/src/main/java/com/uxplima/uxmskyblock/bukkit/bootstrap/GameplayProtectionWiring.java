package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.performance.IslandRedstoneOptimizationListener;
import com.uxplima.uxmskyblock.bukkit.protection.CategoricalInteractablesListener;
import com.uxplima.uxmskyblock.bukkit.protection.ObsidianRecoveryListener;
import com.uxplima.uxmskyblock.bukkit.protection.VoidProtectionListener;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.bukkit.ward.KineticWardListener;
import com.uxplima.uxmskyblock.bukkit.world.AsyncStructureSuppressionListener;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService;
import com.uxplima.uxmskyblock.core.application.ward.KineticWardService;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/**
 * Encapsulates protection mechanics, kinetic wards, void protection, interactables,
 * redstone throttling listeners, and world structure suppression listeners.
 */
public final class GameplayProtectionWiring {

    private final KineticWardService kineticWardService;
    private final KineticWardListener kineticWardListener;
    private final ObsidianRecoveryListener obsidianRecoveryListener;
    private final VoidProtectionListener voidProtectionListener;
    private final CategoricalInteractablesListener categoricalInteractablesListener;
    private final IslandRedstoneOptimizationListener redstoneOptimizationListener;
    private final AsyncStructureSuppressionListener structureSuppressionListener;
    private final com.uxplima.uxmskyblock.bukkit.listener.IslandActionPermissionListener actionPermissionListener;

    public GameplayProtectionWiring(
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            IslandProtectionListener protectionListener,
            TemporaryAccessService temporaryAccessService) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(persistence, "persistence");
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(protectionListener, "protectionListener");
        Objects.requireNonNull(temporaryAccessService, "temporaryAccessService");

        this.kineticWardService = new KineticWardService(
                config.protectionConfig().kineticWardRadius(),
                config.protectionConfig().kineticWardForce(),
                config.protectionConfig().kineticWardVerticalLift());
        this.kineticWardListener = new KineticWardListener(config.protectionConfig(), this.kineticWardService);

        this.obsidianRecoveryListener = new ObsidianRecoveryListener(config.protectionConfig());

        // What every interaction fires is the operator's list, not two lines of Java each.
        com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer effectPlayer =
                new com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer();
        this.kineticWardListener.useEffects(config.effectsConfig(), effectPlayer);
        this.obsidianRecoveryListener.useEffects(config.effectsConfig(), effectPlayer);
        this.voidProtectionListener = new VoidProtectionListener(
                config.protectionConfig(),
                config.settingsConfig(),
                protectionListener::findIslandAt,
                java.time.Clock.systemUTC(),
                config.messages());

        this.categoricalInteractablesListener = new CategoricalInteractablesListener(
                config.interactablesConfig(),
                protectionListener::findIslandAt,
                uuid -> authority
                        .sessionCoordinator()
                        .activeProfile(uuid.value())
                        .orElse(null),
                temporaryAccessService,
                config.messages());
        this.categoricalInteractablesListener.setNodeIdentitySupplier(authority::nodeProcessIdentity);
        // Asked through the wiring rather than resolved here, the same way the node identity above
        // is, so building this does not depend on the order the two were made in.
        this.categoricalInteractablesListener.setProfileTypeProvider(
                profileId -> authority.profileTypes().of(profileId));
        this.categoricalInteractablesListener.setSessionRecordProvider(uuid -> {
            ActiveSession session = authority.sessionCoordinator().getActiveSession(uuid.value());
            if (session == null || session.isFenced()) {
                return Optional.empty();
            }
            return persistence.sessionAuthorityPort().findSession(uuid);
        });

        // The permissions the protection rules never asked for: the bucket, the spawner, the animals
        // and the crops. They ask the protection listener's own gate, so they cost no extra read.
        this.actionPermissionListener = new com.uxplima.uxmskyblock.bukkit.listener.IslandActionPermissionListener(
                protectionListener, config.messages());

        this.redstoneOptimizationListener =
                new IslandRedstoneOptimizationListener(config.settingsConfig(), protectionListener::findIslandAt);
        this.structureSuppressionListener = new AsyncStructureSuppressionListener(config.worldConfig());
    }

    public KineticWardService kineticWardService() {
        return kineticWardService;
    }

    public KineticWardListener kineticWardListener() {
        return kineticWardListener;
    }

    public ObsidianRecoveryListener obsidianRecoveryListener() {
        return obsidianRecoveryListener;
    }

    public VoidProtectionListener voidProtectionListener() {
        return voidProtectionListener;
    }

    /** The rules for the island permissions the protection rules never asked for. */
    public com.uxplima.uxmskyblock.bukkit.listener.IslandActionPermissionListener actionPermissionListener() {
        return actionPermissionListener;
    }

    public CategoricalInteractablesListener categoricalInteractablesListener() {
        return categoricalInteractablesListener;
    }

    public IslandRedstoneOptimizationListener redstoneOptimizationListener() {
        return redstoneOptimizationListener;
    }

    public AsyncStructureSuppressionListener structureSuppressionListener() {
        return structureSuppressionListener;
    }
}

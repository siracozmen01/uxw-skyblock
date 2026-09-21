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
        this.categoricalInteractablesListener.setSessionRecordProvider(uuid -> {
            ActiveSession session = authority.sessionCoordinator().getActiveSession(uuid.value());
            if (session == null || session.isFenced()) {
                return Optional.empty();
            }
            return persistence.sessionAuthorityPort().findSession(uuid);
        });

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

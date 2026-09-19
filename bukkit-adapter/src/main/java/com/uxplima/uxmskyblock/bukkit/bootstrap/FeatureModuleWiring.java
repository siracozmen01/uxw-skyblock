package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.module.BukkitModuleContext;
import com.uxplima.uxmskyblock.bukkit.module.builtin.AllianceFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.AntiAbuseFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.BankModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.BankUpkeepFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.BiomesModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.BoosterFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.BoundaryFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.ChatFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.CoreModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.DimensionFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.DiscordFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.FreezeFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.InactivityFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.LimitFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.MissionFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.PresetsModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.RecycleFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.RewardInboxFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.SeasonFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.ShopFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.SocialFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.TemporaryAccessFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.VaultFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.WarpFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.WorthFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleRegistry;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/**
 * Manages modular feature subsystem registration, configuration toggles,
 * and lifecycle enablement / disablement.
 */
public final class FeatureModuleWiring implements AutoCloseable {

    private final ModuleRegistry moduleRegistry;
    private final BukkitModuleContext moduleContext;
    private final BankUpkeepFeatureModule bankUpkeepFeatureModule;

    public FeatureModuleWiring(
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            GameplayWiring gameplay,
            IntegrationWiring integration) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(persistence, "persistence must not be null");
        Objects.requireNonNull(gameplay, "gameplay must not be null");
        Objects.requireNonNull(integration, "integration must not be null");

        String worldName = config.nodeConfig().worldName();
        ServerNodeId serverNodeId = config.nodeConfig().nodeId();

        this.moduleRegistry = new ModuleRegistry();
        this.moduleContext = new BukkitModuleContext("1.0.0");

        this.bankUpkeepFeatureModule = new BankUpkeepFeatureModule(
                gameplay.bankruptcyService(),
                config.bankConfig(),
                gameplay.scheduler(),
                persistence.islandStoragePort(),
                worldName,
                serverNodeId);

        this.moduleRegistry.register(new CoreModule(gameplay.createIslandUseCase()));
        this.moduleRegistry.register(new BankModule(gameplay.bankService()));
        this.moduleRegistry.register(gameplay.upgradesModule());
        this.moduleRegistry.register(new BiomesModule(gameplay.biomeAdapter()));
        this.moduleRegistry.register(new PresetsModule(gameplay.presetCatalog(), gameplay.schematicEngine()));
        this.moduleRegistry.register(
                new SeasonFeatureModule(gameplay.seasonService(), gameplay.scheduler(), config.seasonConfig()));
        this.moduleRegistry.register(new SocialFeatureModule(gameplay.socialService()));
        this.moduleRegistry.register(new DiscordFeatureModule(integration.discordService()));
        this.moduleRegistry.register(new AllianceFeatureModule(gameplay.allianceService()));
        this.moduleRegistry.register(new ShopFeatureModule(gameplay.dynamicPricingEngine()));
        this.moduleRegistry.register(new TemporaryAccessFeatureModule(
                gameplay.temporaryAccessService(), gameplay.scheduler(), config.temporaryAccessConfig()));
        this.moduleRegistry.register(new RewardInboxFeatureModule(
                gameplay.rewardInboxService(), gameplay.scheduler(), config.rewardConfig()));
        this.moduleRegistry.register(
                new WarpFeatureModule(gameplay.warpService(), gameplay.safeTeleportEngine(), config.warpConfig()));
        this.moduleRegistry.register(new VaultFeatureModule(gameplay.vaultService(), config.vaultConfig()));
        this.moduleRegistry.register(new ChatFeatureModule(gameplay.chatService(), config.chatConfig()));
        this.moduleRegistry.register(new InactivityFeatureModule(
                gameplay.inactivityService(), gameplay.scheduler(), config.inactivityConfig(), worldName));
        this.moduleRegistry.register(new FreezeFeatureModule(gameplay.freezeService()));
        this.moduleRegistry.register(
                new MissionFeatureModule(gameplay.missionService(), config.missionConfig(), gameplay.scheduler()));
        this.moduleRegistry.register(new BoundaryFeatureModule(
                gameplay.boundaryService(), gameplay.boundaryListener(), gameplay.scheduler()));
        this.moduleRegistry.register(new RecycleFeatureModule(gameplay.recycleService()));
        this.moduleRegistry.register(new WorthFeatureModule(gameplay.worthService()));
        this.moduleRegistry.register(new DimensionFeatureModule(gameplay.dimensionService()));
        this.moduleRegistry.register(new LimitFeatureModule(gameplay.limitService()));
        this.moduleRegistry.register(new AntiAbuseFeatureModule(gameplay.antiAbuseService()));
        this.moduleRegistry.register(
                new BoosterFeatureModule(gameplay.boosterService(), config.boosterConfig(), gameplay.scheduler()));
        this.moduleRegistry.register(bankUpkeepFeatureModule);

        this.moduleRegistry.configure(
                config.moduleSettings().moduleToggles(), config.moduleSettings().selectedProviders());
    }

    public void enable() {
        moduleRegistry.enableModules(moduleContext);
    }

    public ModuleRegistry moduleRegistry() {
        return moduleRegistry;
    }

    public BukkitModuleContext moduleContext() {
        return moduleContext;
    }

    public BankUpkeepFeatureModule bankUpkeepFeatureModule() {
        return bankUpkeepFeatureModule;
    }

    @Override
    public void close() {
        moduleRegistry.disableModules();
    }
}

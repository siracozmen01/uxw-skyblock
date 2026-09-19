package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import com.uxplima.uxmskyblock.bukkit.config.BankConfiguration;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * Built-in feature module managing island bank upkeep cycles, two-stage bankruptcy
 * failure escalation (GRACE and LOCKED), and debt remediation (Section 2.39).
 */
public final class BankUpkeepFeatureModule extends AbstractFeatureModule {

    private final IslandBankruptcyService bankruptcyService;
    private final BankConfiguration configuration;
    private final SchedulerPort scheduler;
    private final Supplier<List<Island>> islandsSupplier;
    private final ServerNodeId serverNodeId;
    private @Nullable AutoCloseable upkeepTask;

    public BankUpkeepFeatureModule(
            IslandBankruptcyService bankruptcyService,
            BankConfiguration configuration,
            SchedulerPort scheduler,
            Supplier<List<Island>> islandsSupplier,
            ServerNodeId serverNodeId) {
        super(new ModuleDescriptor(
                "bank-upkeep",
                "1.0.0",
                List.of("core >= 1.0.0"),
                List.of(),
                List.of("island-bank-upkeep", "bankruptcy-protection"),
                ">=1.0.0",
                false));
        this.bankruptcyService = Objects.requireNonNull(bankruptcyService, "bankruptcyService must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.islandsSupplier = Objects.requireNonNull(islandsSupplier, "islandsSupplier must not be null");
        this.serverNodeId = Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
    }

    public BankUpkeepFeatureModule(
            IslandBankruptcyService bankruptcyService,
            BankConfiguration configuration,
            SchedulerPort scheduler,
            IslandStoragePort islandStoragePort,
            String worldName,
            ServerNodeId serverNodeId) {
        this(
                bankruptcyService,
                configuration,
                scheduler,
                () -> Objects.requireNonNull(islandStoragePort, "islandStoragePort")
                        .findAllByWorld(worldName),
                serverNodeId);
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandBankruptcyService.class, bankruptcyService);
        context.registerService(BankConfiguration.class, configuration);

        if (configuration.upkeepPolicy().enabled()) {
            this.upkeepTask = scheduler.repeatAsync(
                    () -> runUpkeepCycle(Instant.now()),
                    Duration.ofSeconds(60),
                    configuration.upkeepPolicy().interval());
        }
    }

    @Override
    protected void onDisable() {
        if (upkeepTask != null) {
            try {
                upkeepTask.close();
            } catch (Exception expected) {
                // Best-effort cancellation
            }
            upkeepTask = null;
        }
    }

    /**
     * Executes an upkeep debit cycle across all loaded islands.
     */
    public void runUpkeepCycle(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (!configuration.upkeepPolicy().enabled()) {
            return;
        }

        List<Island> islands = islandsSupplier.get();
        for (Island island : islands) {
            try {
                bankruptcyService.processUpkeepCycle(
                        island.id(), island.members().size(), now, serverNodeId);
            } catch (Exception ignored) {
                // Keep processing remaining islands
            }
        }
    }

    public IslandBankruptcyService bankruptcyService() {
        return bankruptcyService;
    }

    public BankConfiguration configuration() {
        return configuration;
    }
}

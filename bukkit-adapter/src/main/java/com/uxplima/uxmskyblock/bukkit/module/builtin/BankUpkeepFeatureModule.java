package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.uxplima.uxmskyblock.bukkit.config.BankConfiguration;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankruptcyCycleResult;
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

    /** How often the cycle looks at the clock. A period it already finished costs nothing. */
    private static final Duration POLL = Duration.ofMinutes(1);

    /** The last period every island was charged for, paid or owed, on this node. */
    private volatile long settledPeriod = Long.MIN_VALUE;

    /** The islands of an unsettled period that still owe their charge, retried without a new read. */
    private volatile long pendingPeriod = Long.MIN_VALUE;

    private volatile List<Island> pending = List.of();

    /** A long cycle and the next poll never run at once. */
    private final AtomicBoolean running = new AtomicBoolean();

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
            // The cycle looks every minute and charges once per period. It used to run once per
            // interval from whenever the server started, so a restart charged again at once, and a
            // run that slid across a period boundary could skip one.
            Duration interval = configuration.upkeepPolicy().interval();
            Duration poll = interval.compareTo(POLL) < 0 ? interval : POLL;
            this.upkeepTask = scheduler.repeatAsync(() -> runUpkeepCycle(Instant.now()), Duration.ofSeconds(60), poll);
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
     * Charges every island its upkeep for the period {@code now} falls in, once.
     *
     * <p>A period this node already settled is skipped without asking the database anything. An
     * island whose charge was deferred or failed is tried again on the next poll, alone.
     */
    public void runUpkeepCycle(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (!configuration.upkeepPolicy().enabled()) {
            return;
        }
        long period = configuration.upkeepPolicy().periodOf(now);
        if (period == settledPeriod || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            List<Island> islands = period == pendingPeriod ? pending : islandsSupplier.get();
            List<Island> retry = new ArrayList<>();
            for (Island island : islands) {
                try {
                    BankruptcyCycleResult result = bankruptcyService.processUpkeepCycle(
                            island.id(), island.members().size(), now, serverNodeId);
                    if (result instanceof BankruptcyCycleResult.Deferred) {
                        retry.add(island);
                    }
                } catch (Exception ignored) {
                    // Keep processing remaining islands, and come back for this one
                    retry.add(island);
                }
            }
            if (retry.isEmpty()) {
                settledPeriod = period;
            } else {
                pending = List.copyOf(retry);
                pendingPeriod = period;
            }
        } finally {
            running.set(false);
        }
    }

    public IslandBankruptcyService bankruptcyService() {
        return bankruptcyService;
    }

    public BankConfiguration configuration() {
        return configuration;
    }
}

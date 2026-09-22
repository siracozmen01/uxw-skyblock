package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;
import org.jspecify.annotations.Nullable;

/**
 * Built-in bank module providing island multi-currency treasury and transactional accounting.
 *
 * <p>It also sweeps the idempotency records the bank writes. Every deposit, withdrawal, upgrade
 * purchase, shop trade and upkeep charge writes one so a retry of it is answered rather than applied
 * twice, and nothing ever deleted one: the table held every money movement a server had ever made.
 */
public final class BankModule extends AbstractFeatureModule {

    private static final Logger LOGGER = Logger.getLogger(BankModule.class.getName());

    /** How long a settled idempotency record is kept, when the operator names no other number. */
    public static final Duration DEFAULT_OPERATION_RETENTION = Duration.ofDays(30);

    /** How often the settled records are swept. */
    public static final Duration SWEEP_INTERVAL = Duration.ofHours(6);

    private final IslandBankService bankService;
    private final @Nullable IslandBankPort bankPort;
    private final @Nullable SchedulerPort scheduler;
    private final Duration operationRetention;

    private @Nullable AutoCloseable sweepTask;

    public BankModule(IslandBankService bankService) {
        this(bankService, null, null, DEFAULT_OPERATION_RETENTION);
    }

    /** The canonical constructor, carrying what the sweep needs. */
    public BankModule(
            IslandBankService bankService,
            @Nullable IslandBankPort bankPort,
            @Nullable SchedulerPort scheduler,
            Duration operationRetention) {
        super(new ModuleDescriptor(
                "bank", "1.0.0", List.of("core >= 1.0.0"), List.of(), List.of("island-bank"), ">=1.0.0", false));
        this.bankService = Objects.requireNonNull(bankService, "bankService must not be null");
        this.bankPort = bankPort;
        this.scheduler = scheduler;
        this.operationRetention = Objects.requireNonNull(operationRetention, "operationRetention must not be null");
        if (operationRetention.isNegative()) {
            throw new IllegalArgumentException("operationRetention must not be negative: " + operationRetention);
        }
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandBankService.class, bankService);
        if (bankPort != null && scheduler != null) {
            this.sweepTask = scheduler.repeatAsync(this::sweepSettledOperations, SWEEP_INTERVAL, SWEEP_INTERVAL);
        }
    }

    /** Deletes what has settled and is old enough not to be worth keeping. Package private for the test. */
    int sweepSettledOperations() {
        IslandBankPort port = this.bankPort;
        if (port == null) {
            return 0;
        }
        try {
            int swept = port.purgeSettledOperationsBefore(Instant.now().minus(operationRetention));
            if (swept > 0) {
                LOGGER.fine(() -> "Swept " + swept + " settled bank operations.");
            }
            return swept;
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Sweeping settled bank operations failed. The bank carries on.");
            return 0;
        }
    }

    @Override
    protected void onDisable() {
        if (sweepTask != null) {
            try {
                sweepTask.close();
            } catch (Exception expected) {
                // Best-effort cancellation
            }
            sweepTask = null;
        }
    }
}

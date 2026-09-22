package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;
import org.jspecify.annotations.Nullable;

/**
 * Built-in feature module managing shared island vaults, pessimistic page locking,
 * escrow journaling, and security audit logs.
 *
 * <p>It also closes the edit sessions whose lease ran out. A player who crashes or is disconnected
 * with a page open leaves the session ACTIVE and the page still pointing at it. The query that
 * finds those was written and had no caller, so the rows stayed for as long as the server ran.
 */
public final class VaultFeatureModule extends AbstractFeatureModule {

    private static final Logger LOGGER = Logger.getLogger(VaultFeatureModule.class.getName());

    private final IslandVaultService vaultService;
    private final VaultConfiguration configuration;
    private final @Nullable SchedulerPort scheduler;

    private @Nullable AutoCloseable sweepTask;

    public VaultFeatureModule(IslandVaultService vaultService, VaultConfiguration configuration) {
        this(vaultService, configuration, null);
    }

    /** The canonical constructor, carrying the scheduler the sweep runs on. */
    public VaultFeatureModule(
            IslandVaultService vaultService, VaultConfiguration configuration, @Nullable SchedulerPort scheduler) {
        super(new ModuleDescriptor(
                "vault",
                "1.0.0",
                List.of("core >= 1.0.0"),
                List.of(),
                List.of("island-vault", "vault-paged-inventory"),
                ">=1.0.0",
                false));
        this.vaultService = Objects.requireNonNull(vaultService, "vaultService must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.scheduler = scheduler;
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandVaultService.class, vaultService);
        SchedulerPort port = this.scheduler;
        if (port != null) {
            this.sweepTask = port.repeatAsync(
                    this::closeExpiredSessions,
                    configuration.expiredSessionSweepInterval(),
                    configuration.expiredSessionSweepInterval());
        }
    }

    /** Closes the sessions whose lease ran out. Package private so the test can run one sweep. */
    int closeExpiredSessions() {
        try {
            return vaultService.closeExpiredSessions();
        } catch (RuntimeException e) {
            // The next sweep tries again. A failed one must not take the repeating task down with it.
            LOGGER.log(Level.WARNING, e, () -> "Closing the expired vault sessions failed. The next sweep retries.");
            return 0;
        }
    }

    @Override
    protected void onDisable() {
        AutoCloseable task = this.sweepTask;
        this.sweepTask = null;
        if (task == null) {
            return;
        }
        try {
            task.close();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, e, () -> "Cancelling the vault sweep failed.");
        }
    }

    public VaultConfiguration configuration() {
        return configuration;
    }
}

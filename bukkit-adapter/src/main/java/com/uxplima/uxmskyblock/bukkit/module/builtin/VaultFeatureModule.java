package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;

/**
 * Built-in feature module managing shared island vaults, pessimistic page locking,
 * escrow journaling, and security audit logs.
 */
public final class VaultFeatureModule extends AbstractFeatureModule {

    private final IslandVaultService vaultService;
    private final VaultConfiguration configuration;

    public VaultFeatureModule(IslandVaultService vaultService, VaultConfiguration configuration) {
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
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandVaultService.class, vaultService);
    }

    @Override
    protected void onDisable() {
        // No persistent resources to unbind
    }

    public VaultConfiguration configuration() {
        return configuration;
    }
}

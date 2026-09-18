package com.uxplima.uxmskyblock.bukkit.module.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.bukkit.module.BukkitModuleContext;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VaultFeatureModuleTest {

    @Test
    @DisplayName("module registers IslandVaultService upon enable")
    void registersServiceOnEnable() {
        IslandVaultService vaultService = mock(IslandVaultService.class);
        VaultConfiguration config = VaultConfiguration.defaultConfiguration();
        VaultFeatureModule module = new VaultFeatureModule(vaultService, config);

        assertThat(module.descriptor().id()).isEqualTo("vault");
        assertThat(module.descriptor().provides()).contains("island-vault", "vault-paged-inventory");
        assertThat(module.configuration()).isSameAs(config);

        BukkitModuleContext context = mock(BukkitModuleContext.class);
        module.enable(context);

        verify(context).registerService(IslandVaultService.class, vaultService);
        assertThat(module.state()).isEqualTo(com.uxplima.uxmskyblock.core.domain.module.ModuleState.ENABLED);

        module.disable();
        assertThat(module.state()).isEqualTo(com.uxplima.uxmskyblock.core.domain.module.ModuleState.DISABLED);
    }
}

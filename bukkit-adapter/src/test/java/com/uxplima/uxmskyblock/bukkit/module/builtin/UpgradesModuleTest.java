package com.uxplima.uxmskyblock.bukkit.module.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.uxplima.uxmskyblock.bukkit.config.GeneratorsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.UpgradesConfiguration;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.domain.module.ModuleState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UpgradesModuleTest {

    @Test
    @DisplayName("module registers services upon enable and handles disable cleanly")
    void registersServicesOnEnable() {
        IslandUpgradeService service = mock(IslandUpgradeService.class);
        UpgradesConfiguration upgradesConfig = UpgradesConfiguration.defaultConfiguration();
        GeneratorsConfiguration genConfig = GeneratorsConfiguration.defaultConfiguration();

        UpgradesModule module = new UpgradesModule(service, upgradesConfig, genConfig, null, null);

        assertThat(module.descriptor().id()).isEqualTo("upgrades");
        assertThat(module.descriptor().provides()).contains("island-upgrades");
        assertThat(module.upgradeService()).isSameAs(service);
        assertThat(module.upgradesConfiguration()).isSameAs(upgradesConfig);
        assertThat(module.generatorsConfiguration()).isSameAs(genConfig);

        ModuleContext context = mock(ModuleContext.class);
        module.enable(context);

        assertThat(module.state()).isEqualTo(ModuleState.ENABLED);
        verify(context).registerService(IslandUpgradeService.class, service);
        verify(context).registerService(UpgradesConfiguration.class, upgradesConfig);
        verify(context).registerService(GeneratorsConfiguration.class, genConfig);

        module.disable();
        assertThat(module.state()).isEqualTo(ModuleState.DISABLED);
    }
}

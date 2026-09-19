package com.uxplima.uxmskyblock.bukkit.module.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.domain.module.ModuleState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorthFeatureModuleTest {

    @Test
    @DisplayName("module registers IslandWorthService upon enable and handles disable")
    void registersServiceOnEnable() {
        IslandWorthService worthService = mock(IslandWorthService.class);
        WorthFeatureModule module = new WorthFeatureModule(worthService);

        assertThat(module.descriptor().id()).isEqualTo("worth");
        assertThat(module.descriptor().provides())
                .contains("island-worth", "island-level", "block-valuation", "levels-engine");
        assertThat(module.worthService()).isSameAs(worthService);

        ModuleContext context = mock(ModuleContext.class);
        module.enable(context);

        assertThat(module.state()).isEqualTo(ModuleState.ENABLED);
        verify(context).registerService(IslandWorthService.class, worthService);

        module.disable();
        assertThat(module.state()).isEqualTo(ModuleState.DISABLED);
    }
}

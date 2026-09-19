package com.uxplima.uxmskyblock.bukkit.module.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.uxplima.uxmskyblock.core.application.dimension.IslandDimensionService;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.domain.module.ModuleState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DimensionFeatureModuleTest {

    @Test
    @DisplayName("module registers IslandDimensionService upon enable and handles disable")
    void registersServiceOnEnable() {
        IslandDimensionService dimensionService = mock(IslandDimensionService.class);
        DimensionFeatureModule module = new DimensionFeatureModule(dimensionService);

        assertThat(module.descriptor().id()).isEqualTo("dimensions");
        assertThat(module.descriptor().provides()).contains("island-dimensions", "multi-dimension", "portal-linkage");
        assertThat(module.dimensionService()).isSameAs(dimensionService);

        ModuleContext context = mock(ModuleContext.class);
        module.enable(context);

        assertThat(module.state()).isEqualTo(ModuleState.ENABLED);
        verify(context).registerService(IslandDimensionService.class, dimensionService);

        module.disable();
        assertThat(module.state()).isEqualTo(ModuleState.DISABLED);
    }
}

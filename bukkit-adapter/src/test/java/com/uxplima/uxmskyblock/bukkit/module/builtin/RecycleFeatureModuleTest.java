package com.uxplima.uxmskyblock.bukkit.module.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.domain.module.ModuleState;

class RecycleFeatureModuleTest {

    @Test
    @DisplayName("module registers IslandRecycleService upon enable")
    void registersServiceOnEnable() {
        IslandRecycleService recycleService = mock(IslandRecycleService.class);
        RecycleFeatureModule module = new RecycleFeatureModule(recycleService);

        assertThat(module.descriptor().id()).isEqualTo("recycle");
        assertThat(module.descriptor().provides()).contains("island-recycle", "island-reset", "island-delete");
        assertThat(module.recycleService()).isSameAs(recycleService);

        ModuleContext context = mock(ModuleContext.class);
        module.enable(context);

        assertThat(module.state()).isEqualTo(ModuleState.ENABLED);
        verify(context).registerService(IslandRecycleService.class, recycleService);

        module.disable();
        assertThat(module.state()).isEqualTo(ModuleState.DISABLED);
    }
}

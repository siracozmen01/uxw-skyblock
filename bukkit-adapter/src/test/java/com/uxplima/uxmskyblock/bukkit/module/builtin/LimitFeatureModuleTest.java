package com.uxplima.uxmskyblock.bukkit.module.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.domain.module.ModuleState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LimitFeatureModuleTest {

    @Test
    @DisplayName("module registers IslandLimitService upon enable and handles disable")
    void registersServiceOnEnable() {
        IslandLimitService limitService = mock(IslandLimitService.class);
        LimitFeatureModule module = new LimitFeatureModule(limitService);

        assertThat(module.descriptor().id()).isEqualTo("limits");
        assertThat(module.descriptor().provides()).contains("island-limits", "anti-lag");
        assertThat(module.limitService()).isSameAs(limitService);

        ModuleContext context = mock(ModuleContext.class);
        module.enable(context);

        assertThat(module.state()).isEqualTo(ModuleState.ENABLED);
        verify(context).registerService(IslandLimitService.class, limitService);

        module.disable();
        assertThat(module.state()).isEqualTo(ModuleState.DISABLED);
    }
}

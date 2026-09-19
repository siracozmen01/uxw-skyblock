package com.uxplima.uxmskyblock.bukkit.module.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.domain.module.ModuleState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AntiAbuseFeatureModuleTest {

    @Test
    @DisplayName("module registers IslandAntiAbuseService upon enable and handles disable")
    void registersServiceOnEnable() {
        IslandAntiAbuseService service = mock(IslandAntiAbuseService.class);
        AntiAbuseFeatureModule module = new AntiAbuseFeatureModule(service);

        assertThat(module.descriptor().id()).isEqualTo("anti-abuse");
        assertThat(module.descriptor().provides()).contains("starter-protection", "anti-alt", "coop-hopping-lock");
        assertThat(module.antiAbuseService()).isSameAs(service);

        ModuleContext context = mock(ModuleContext.class);
        module.enable(context);

        assertThat(module.state()).isEqualTo(ModuleState.ENABLED);
        verify(context).registerService(IslandAntiAbuseService.class, service);

        module.disable();
        assertThat(module.state()).isEqualTo(ModuleState.DISABLED);
    }
}

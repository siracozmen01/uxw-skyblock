package com.uxplima.uxmskyblock.bukkit.module.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uxplima.uxmskyblock.bukkit.config.BoosterConfiguration;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.module.ModuleState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BoosterFeatureModuleTest {

    @Test
    @DisplayName("module registers IslandBoosterService upon enable and handles disable")
    void registersServiceOnEnable() throws Exception {
        IslandBoosterService service = mock(IslandBoosterService.class);
        BoosterConfiguration config = BoosterConfiguration.defaultConfiguration();
        SchedulerPort scheduler = mock(SchedulerPort.class);
        AutoCloseable task = mock(AutoCloseable.class);

        when(scheduler.repeatAsync(any(), any(), any())).thenReturn(task);

        BoosterFeatureModule module = new BoosterFeatureModule(service, config, scheduler);

        assertThat(module.descriptor().id()).isEqualTo("boosters");
        assertThat(module.descriptor().provides()).contains("island-boosters", "multipliers", "pause-on-idle");
        assertThat(module.boosterService()).isSameAs(service);
        assertThat(module.configuration()).isSameAs(config);

        ModuleContext context = mock(ModuleContext.class);
        module.enable(context);

        assertThat(module.state()).isEqualTo(ModuleState.ENABLED);
        verify(context).registerService(IslandBoosterService.class, service);
        verify(context).registerService(BoosterConfiguration.class, config);
        verify(scheduler).repeatAsync(any(), any(), any());

        module.disable();
        assertThat(module.state()).isEqualTo(ModuleState.DISABLED);
        verify(task).close();
    }
}

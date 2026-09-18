package com.uxplima.uxmskyblock.bukkit.module.builtin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import com.uxplima.uxmskyblock.bukkit.config.InactivityConfiguration;
import com.uxplima.uxmskyblock.core.application.inactivity.IslandInactivityService;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InactivityFeatureModuleTest {

    @Test
    @DisplayName("module registers IslandInactivityService and starts recurring scan when enabled")
    void registersServiceAndSchedulesScanWhenEnabled() {
        IslandInactivityService inactivityService = mock(IslandInactivityService.class);
        SchedulerPort scheduler = mock(SchedulerPort.class);
        AutoCloseable task = mock(AutoCloseable.class);
        when(scheduler.repeatAsync(any(), any(), any())).thenReturn(task);

        InactivityConfiguration config = InactivityConfiguration.defaultConfiguration();
        InactivityFeatureModule module = new InactivityFeatureModule(inactivityService, scheduler, config, "world");

        ModuleContext context = mock(ModuleContext.class);
        module.enable(context);

        verify(context).registerService(IslandInactivityService.class, inactivityService);
        verify(scheduler).repeatAsync(any(), any(), any());

        module.disable();
        try {
            verify(task).close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("module does not schedule scan when disabled in configuration")
    void doesNotScheduleScanWhenDisabled() {
        IslandInactivityService inactivityService = mock(IslandInactivityService.class);
        SchedulerPort scheduler = mock(SchedulerPort.class);

        InactivityConfiguration config = new InactivityConfiguration(
                false,
                Duration.ofDays(1),
                Duration.ofDays(30),
                Duration.ofDays(60),
                InactivityConfiguration.DEFAULT_SUCCESSION_HIERARCHY,
                InactivityConfiguration.DEFAULT_FORMER_OWNER_ACTION,
                InactivityConfiguration.DEFAULT_ABANDONMENT_ACTION);

        InactivityFeatureModule module = new InactivityFeatureModule(inactivityService, scheduler, config, "world");

        ModuleContext context = mock(ModuleContext.class);
        module.enable(context);

        verify(context).registerService(IslandInactivityService.class, inactivityService);
        verify(scheduler, never()).repeatAsync(any(), any(), any());
    }
}

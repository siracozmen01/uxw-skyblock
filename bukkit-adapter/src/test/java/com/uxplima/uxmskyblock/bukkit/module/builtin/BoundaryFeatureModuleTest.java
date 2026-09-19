package com.uxplima.uxmskyblock.bukkit.module.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import com.uxplima.uxmskyblock.bukkit.boundary.IslandBoundaryListener;
import com.uxplima.uxmskyblock.bukkit.module.BukkitModuleContext;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.module.ModuleState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BoundaryFeatureModuleTest {

    @Test
    @DisplayName("module registers IslandBoundaryService upon enable and schedules ticker")
    void registersServiceOnEnable() throws Exception {
        IslandBoundaryService boundaryService = mock(IslandBoundaryService.class);
        IslandBoundaryListener boundaryListener = mock(IslandBoundaryListener.class);
        SchedulerPort schedulerPort = mock(SchedulerPort.class);
        AutoCloseable cancellableTask = mock(AutoCloseable.class);

        when(schedulerPort.repeatGlobal(any(), any(Duration.class), any(Duration.class)))
                .thenReturn(cancellableTask);

        BoundaryFeatureModule module = new BoundaryFeatureModule(boundaryService, boundaryListener, schedulerPort);

        assertThat(module.descriptor().id()).isEqualTo("boundary");
        assertThat(module.descriptor().provides())
                .contains("island-boundary", "boundary-shield", "virtual-worldborder");
        assertThat(module.boundaryService()).isSameAs(boundaryService);

        BukkitModuleContext context = mock(BukkitModuleContext.class);
        module.enable(context);

        verify(context).registerService(IslandBoundaryService.class, boundaryService);
        verify(schedulerPort).repeatGlobal(any(), any(Duration.class), any(Duration.class));
        assertThat(module.state()).isEqualTo(ModuleState.ENABLED);

        module.disable();
        verify(cancellableTask).close();
        assertThat(module.state()).isEqualTo(ModuleState.DISABLED);
    }
}

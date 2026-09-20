package com.uxplima.uxmskyblock.bukkit.module.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uxplima.uxmskyblock.bukkit.config.MissionConfiguration;
import com.uxplima.uxmskyblock.bukkit.module.BukkitModuleContext;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.domain.module.ModuleState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MissionFeatureModuleTest {

    @Test
    @DisplayName("module registers IslandMissionService upon enable")
    void registersServiceOnEnable() {
        IslandMissionService missionService = mock(IslandMissionService.class);
        MissionConfiguration config = MissionConfiguration.defaultConfiguration();
        MissionFeatureModule module = new MissionFeatureModule(missionService, config);

        assertThat(module.descriptor().id()).isEqualTo("missions");
        assertThat(module.descriptor().provides()).contains("island-missions", "island-quests");
        assertThat(module.configuration()).isSameAs(config);

        BukkitModuleContext context = mock(BukkitModuleContext.class);
        module.enable(context);

        verify(context).registerService(IslandMissionService.class, missionService);
        assertThat(module.state()).isEqualTo(ModuleState.ENABLED);

        module.disable();
        assertThat(module.state()).isEqualTo(ModuleState.DISABLED);
    }

    @Test
    @DisplayName("module schedules dirty progress flush when scheduler is present")
    void schedulesProgressFlushWithScheduler() throws Exception {
        IslandMissionService missionService = mock(IslandMissionService.class);
        MissionConfiguration config = MissionConfiguration.defaultConfiguration();
        com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort scheduler =
                mock(com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort.class);
        AutoCloseable mockHandle = mock(AutoCloseable.class);

        when(scheduler.repeatAsync(any(), any(), any())).thenReturn(mockHandle);

        MissionFeatureModule module = new MissionFeatureModule(missionService, config, scheduler);
        BukkitModuleContext context = mock(BukkitModuleContext.class);

        module.enable(context);
        verify(scheduler).repeatAsync(any(), any(), any());

        module.disable();
        verify(mockHandle).close();
        verify(missionService).flushDirtyProgress();
    }

    @Test
    @DisplayName("module survives UnsupportedOperationException from scheduler stub")
    void survivesUnsupportedOperationExceptionFromScheduler() {
        IslandMissionService missionService = mock(IslandMissionService.class);
        MissionConfiguration config = MissionConfiguration.defaultConfiguration();
        com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort scheduler =
                mock(com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort.class);

        when(scheduler.repeatAsync(any(), any(), any())).thenThrow(new UnsupportedOperationException("Not supported"));

        MissionFeatureModule module = new MissionFeatureModule(missionService, config, scheduler);
        BukkitModuleContext context = mock(BukkitModuleContext.class);

        module.enable(context);
        assertThat(module.state()).isEqualTo(ModuleState.ENABLED);

        module.disable();
        verify(missionService).flushDirtyProgress();
    }

    @Test
    @DisplayName("module throws IllegalStateException on unexpected scheduler exception")
    void throwsOnUnexpectedSchedulerException() {
        IslandMissionService missionService = mock(IslandMissionService.class);
        MissionConfiguration config = MissionConfiguration.defaultConfiguration();
        com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort scheduler =
                mock(com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort.class);

        when(scheduler.repeatAsync(any(), any(), any())).thenThrow(new RuntimeException("Scheduler crashed"));

        MissionFeatureModule module = new MissionFeatureModule(missionService, config, scheduler);
        BukkitModuleContext context = mock(BukkitModuleContext.class);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> module.enable(context));
    }
}

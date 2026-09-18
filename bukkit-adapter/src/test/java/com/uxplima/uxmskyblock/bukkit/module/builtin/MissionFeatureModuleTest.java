package com.uxplima.uxmskyblock.bukkit.module.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
}

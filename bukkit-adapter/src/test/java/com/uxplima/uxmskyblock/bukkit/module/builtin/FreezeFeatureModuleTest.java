package com.uxplima.uxmskyblock.bukkit.module.builtin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FreezeFeatureModuleTest {

    @Test
    @DisplayName("module registers IslandAdminFreezeService upon enable")
    void registersServiceUponEnable() {
        IslandAdminFreezeService freezeService = mock(IslandAdminFreezeService.class);
        FreezeFeatureModule module = new FreezeFeatureModule(freezeService);

        ModuleContext context = mock(ModuleContext.class);
        module.enable(context);

        verify(context).registerService(IslandAdminFreezeService.class, freezeService);
    }
}

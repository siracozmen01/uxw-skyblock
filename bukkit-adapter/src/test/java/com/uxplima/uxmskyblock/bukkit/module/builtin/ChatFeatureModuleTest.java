package com.uxplima.uxmskyblock.bukkit.module.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.uxplima.uxmskyblock.bukkit.config.ChatConfiguration;
import com.uxplima.uxmskyblock.bukkit.module.BukkitModuleContext;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatService;
import com.uxplima.uxmskyblock.core.domain.module.ModuleState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatFeatureModuleTest {

    @Test
    @DisplayName("module registers IslandChatService upon enable")
    void registersServiceOnEnable() {
        IslandChatService chatService = mock(IslandChatService.class);
        ChatConfiguration config = ChatConfiguration.defaultConfiguration();
        ChatFeatureModule module = new ChatFeatureModule(chatService, config);

        assertThat(module.descriptor().id()).isEqualTo("chat");
        assertThat(module.descriptor().provides()).contains("island-chat", "island-team-channels");
        assertThat(module.configuration()).isSameAs(config);

        BukkitModuleContext context = mock(BukkitModuleContext.class);
        module.enable(context);

        verify(context).registerService(IslandChatService.class, chatService);
        assertThat(module.state()).isEqualTo(ModuleState.ENABLED);

        module.disable();
        assertThat(module.state()).isEqualTo(ModuleState.DISABLED);
    }
}

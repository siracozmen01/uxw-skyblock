package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.config.ChatConfiguration;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatService;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;

/**
 * Built-in feature module managing island private team communications,
 * dual dispatch routing, spy oversight, and rate limiting.
 */
public final class ChatFeatureModule extends AbstractFeatureModule {

    private final IslandChatService chatService;
    private final ChatConfiguration configuration;

    public ChatFeatureModule(IslandChatService chatService, ChatConfiguration configuration) {
        super(new ModuleDescriptor(
                "chat",
                "1.0.0",
                List.of("core >= 1.0.0"),
                List.of(),
                List.of("island-chat", "island-team-channels"),
                ">=1.0.0",
                false));
        this.chatService = Objects.requireNonNull(chatService, "chatService must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandChatService.class, chatService);
    }

    @Override
    protected void onDisable() {
        // No persistent resources to unbind
    }

    public ChatConfiguration configuration() {
        return configuration;
    }
}

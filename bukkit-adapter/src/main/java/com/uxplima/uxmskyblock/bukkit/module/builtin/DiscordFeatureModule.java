package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.discord.IslandDiscordWebhookService;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;

/**
 * Built-in internal feature module managing standalone Discord webhook dispatches,
 * token-bucket rate limiting, and staff alert notifications.
 */
public final class DiscordFeatureModule extends AbstractFeatureModule {

    private final IslandDiscordWebhookService discordService;

    public DiscordFeatureModule(IslandDiscordWebhookService discordService) {
        super(new ModuleDescriptor(
                "discord", "1.0.0", List.of("core >= 1.0.0"), List.of(), List.of("island-discord"), ">=1.0.0", false));
        this.discordService = Objects.requireNonNull(discordService, "discordService must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandDiscordWebhookService.class, discordService);
    }

    @Override
    protected void onDisable() {
        discordService.close();
    }
}

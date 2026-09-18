package com.uxplima.uxmskyblock.core.application.discord;

import java.util.concurrent.CompletableFuture;

import com.uxplima.uxmskyblock.core.domain.discord.DiscordWebhookPayload;

/**
 * Outbound transport port for sending HTTP POST webhook payloads to Discord.
 */
@FunctionalInterface
public interface DiscordWebhookClientPort {

    /**
     * Asynchronously delivers a webhook payload to the target Discord URL.
     *
     * @param webhookUrl the target webhook URL
     * @param payload the webhook payload
     * @return a future completing with true if HTTP status is 2xx, false otherwise
     */
    CompletableFuture<Boolean> sendWebhook(String webhookUrl, DiscordWebhookPayload payload);
}

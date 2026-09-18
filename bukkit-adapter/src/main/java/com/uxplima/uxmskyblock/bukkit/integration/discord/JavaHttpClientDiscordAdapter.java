package com.uxplima.uxmskyblock.bukkit.integration.discord;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.uxplima.uxmskyblock.core.application.discord.DiscordWebhookClientPort;
import com.uxplima.uxmskyblock.core.domain.discord.DiscordWebhookPayload;

/**
 * Production standalone Discord Webhook client using standard Java 25 {@link HttpClient}
 * with zero external dependencies.
 */
public final class JavaHttpClientDiscordAdapter implements DiscordWebhookClientPort {

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public JavaHttpClientDiscordAdapter(HttpClient httpClient, Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
    }

    public JavaHttpClientDiscordAdapter() {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                Duration.ofSeconds(10));
    }

    @Override
    public CompletableFuture<Boolean> sendWebhook(String webhookUrl, DiscordWebhookPayload payload) {
        Objects.requireNonNull(webhookUrl, "webhookUrl must not be null");
        Objects.requireNonNull(payload, "payload must not be null");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "UXPLIMA-Skyblock-DiscordWebhook/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toJson(), StandardCharsets.UTF_8))
                    .build();

            return httpClient
                    .sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenApply(response -> response.statusCode() >= 200 && response.statusCode() < 300)
                    .exceptionally(throwable -> false);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(false);
        }
    }
}

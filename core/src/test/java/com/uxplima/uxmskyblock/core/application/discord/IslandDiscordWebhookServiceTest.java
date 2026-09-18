package com.uxplima.uxmskyblock.core.application.discord;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.uxplima.uxmskyblock.core.domain.discord.DiscordEmbed;
import com.uxplima.uxmskyblock.core.domain.discord.DiscordTopic;
import com.uxplima.uxmskyblock.core.domain.discord.DiscordWebhookPayload;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandDiscordWebhookServiceTest {

    private final List<RecordCall> deliveredCalls = Collections.synchronizedList(new ArrayList<>());
    private IslandDiscordWebhookService service;

    record RecordCall(String url, DiscordWebhookPayload payload) {}

    @BeforeEach
    void setUp() {
        deliveredCalls.clear();
        DiscordWebhookClientPort mockClient = (url, payload) -> {
            deliveredCalls.add(new RecordCall(url, payload));
            return CompletableFuture.completedFuture(true);
        };

        Map<DiscordTopic, String> urls = Map.of(
                DiscordTopic.MILESTONES, "https://discord.com/api/webhooks/milestones",
                DiscordTopic.LEADERBOARDS, "https://discord.com/api/webhooks/leaderboards",
                DiscordTopic.ALLIANCES, "https://discord.com/api/webhooks/alliances",
                DiscordTopic.ADMIN_AUDIT, "https://discord.com/api/webhooks/admin");

        service = new IslandDiscordWebhookService(mockClient, urls, true, "Skyblock Bot", "https://avatar.png", 50.0);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    @DisplayName("DiscordWebhookPayload generates valid escaped JSON")
    void payloadJsonSerialization() {
        DiscordEmbed embed = DiscordEmbed.builder()
                .title("Test \"Title\"")
                .description("Line 1\nLine 2")
                .color(0xFF0000)
                .addField("Field 1", "Value with \\ backslash", true)
                .footer("Test Footer")
                .build();

        DiscordWebhookPayload payload =
                new DiscordWebhookPayload("MyBot", "https://avatar.png", "Hello world!", List.of(embed));

        String json = payload.toJson();
        assertThat(json).contains("\"username\":\"MyBot\"");
        assertThat(json).contains("\"avatar_url\":\"https://avatar.png\"");
        assertThat(json).contains("\"content\":\"Hello world!\"");
        assertThat(json).contains("\"title\":\"Test \\\"Title\\\"\"");
        assertThat(json).contains("\"description\":\"Line 1\\nLine 2\"");
        assertThat(json).contains("\"name\":\"Field 1\"");
        assertThat(json).contains("\"value\":\"Value with \\\\ backslash\"");
        assertThat(json).contains("\"inline\":true");
        assertThat(json).contains("\"footer\":{\"text\":\"Test Footer\"}");
    }

    @Test
    @DisplayName("notifyMilestone dispatches formatted embed to milestones endpoint")
    void notifyMilestoneDispatchesCorrectly() throws Exception {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        service.notifyMilestone(islandId, "Atlantis", "Steve", 1000, List.of("Alex", "Notch"));

        service.flushAndDrain(Duration.ofSeconds(2));

        assertThat(deliveredCalls).hasSize(1);
        RecordCall call = deliveredCalls.get(0);
        assertThat(call.url()).isEqualTo("https://discord.com/api/webhooks/milestones");
        assertThat(call.payload().username()).isEqualTo("Skyblock Bot");
        assertThat(call.payload().embeds()).hasSize(1);

        DiscordEmbed embed = call.payload().embeds().get(0);
        assertThat(embed.title()).contains("Milestone");
        assertThat(embed.description()).contains("Atlantis").contains("1,000");
    }

    @Test
    @DisplayName("notifyLeaderboard formats top islands cleanly")
    void notifyLeaderboardDispatchesCorrectly() throws Exception {
        IslandId id1 = IslandId.of(UUID.randomUUID());
        IslandId id2 = IslandId.of(UUID.randomUUID());
        List<LeaderboardEntry> entries = List.of(
                new LeaderboardEntry(1, id1, "Island Alpha", 50000, "50,000"),
                new LeaderboardEntry(2, id2, "Island Beta", 35000, "35,000"));

        service.notifyLeaderboard("Level", entries);
        service.flushAndDrain(Duration.ofSeconds(2));

        assertThat(deliveredCalls).hasSize(1);
        RecordCall call = deliveredCalls.get(0);
        assertThat(call.url()).isEqualTo("https://discord.com/api/webhooks/leaderboards");

        DiscordEmbed embed = call.payload().embeds().get(0);
        assertThat(embed.title()).contains("Level");
        assertThat(embed.description()).contains("🥇 **Island Alpha**").contains("🥈 **Island Beta**");
    }

    @Test
    @DisplayName("notifyAlliance dispatches to alliances endpoint")
    void notifyAllianceDispatchesCorrectly() throws Exception {
        service.notifyAlliance("Iron Pact", "Created", "Steve", "Alex");
        service.flushAndDrain(Duration.ofSeconds(2));

        assertThat(deliveredCalls).hasSize(1);
        RecordCall call = deliveredCalls.get(0);
        assertThat(call.url()).isEqualTo("https://discord.com/api/webhooks/alliances");
        assertThat(call.payload().embeds().get(0).title()).contains("Created");
    }

    @Test
    @DisplayName("notifyAdminAudit assigns appropriate color and severity tag")
    void notifyAdminAuditDispatchesCorrectly() throws Exception {
        service.notifyAdminAudit(
                "ILLEGAL_DUPE", "CRITICAL", "Duplication exploit detected", Map.of("Player", "Cheater1"));
        service.flushAndDrain(Duration.ofSeconds(2));

        assertThat(deliveredCalls).hasSize(1);
        RecordCall call = deliveredCalls.get(0);
        assertThat(call.url()).isEqualTo("https://discord.com/api/webhooks/admin");

        DiscordEmbed embed = call.payload().embeds().get(0);
        assertThat(embed.color()).isEqualTo(0xE74C3C); // Red for critical
        assertThat(embed.title()).contains("[CRITICAL]");
    }

    @Test
    @DisplayName("disabled service drops notifications silently")
    void disabledServiceDropsNotifications() throws Exception {
        DiscordWebhookClientPort mockClient = (url, payload) -> CompletableFuture.completedFuture(true);
        IslandDiscordWebhookService disabledService = new IslandDiscordWebhookService(
                mockClient, Map.of(DiscordTopic.MILESTONES, "https://url"), false, "Bot", null, 10.0);

        disabledService.notifyMilestone(IslandId.of(UUID.randomUUID()), "Test", "Steve", 100, List.of());
        disabledService.flushAndDrain(Duration.ofMillis(200));

        assertThat(disabledService.queueSize()).isEqualTo(0);
        disabledService.close();
    }
}

package com.uxplima.uxmskyblock.core.application.discord;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmskyblock.core.application.announce.IslandAnnouncer;
import com.uxplima.uxmskyblock.core.domain.discord.DiscordEmbed;
import com.uxplima.uxmskyblock.core.domain.discord.DiscordTopic;
import com.uxplima.uxmskyblock.core.domain.discord.DiscordWebhookPayload;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import org.jspecify.annotations.Nullable;

/**
 * Application service managing standalone Discord webhook notifications,
 * non-blocking token-bucket rate limiting, categorized topic routing,
 * and rich embed formatting.
 */
public final class IslandDiscordWebhookService implements AutoCloseable, IslandAnnouncer {

    public static final String DEFAULT_USERNAME = "UXPLIMA Skyblock";
    public static final double DEFAULT_RATE_LIMIT = 2.0; // 2 requests/second

    private final DiscordWebhookClientPort clientPort;
    private final Map<DiscordTopic, String> webhookUrls;
    private final boolean enabled;
    private final String defaultUsername;
    private final @Nullable String defaultAvatarUrl;
    private final double rateLimitPerSecond;
    private final DiscordEmbedTexts texts;

    private final BlockingQueue<WebhookTask> queue = new LinkedBlockingQueue<>(1000);
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread workerThread;

    public record WebhookTask(String url, DiscordWebhookPayload payload) {}

    public IslandDiscordWebhookService(
            DiscordWebhookClientPort clientPort,
            Map<DiscordTopic, String> webhookUrls,
            boolean enabled,
            String defaultUsername,
            @Nullable String defaultAvatarUrl,
            double rateLimitPerSecond) {
        this(
                clientPort,
                webhookUrls,
                enabled,
                defaultUsername,
                defaultAvatarUrl,
                rateLimitPerSecond,
                DiscordEmbedTexts.english());
    }

    public IslandDiscordWebhookService(
            DiscordWebhookClientPort clientPort,
            Map<DiscordTopic, String> webhookUrls,
            boolean enabled,
            String defaultUsername,
            @Nullable String defaultAvatarUrl,
            double rateLimitPerSecond,
            DiscordEmbedTexts texts) {
        this.texts = Objects.requireNonNull(texts, "texts must not be null");
        this.clientPort = Objects.requireNonNull(clientPort, "clientPort must not be null");
        this.webhookUrls = new EnumMap<>(DiscordTopic.class);
        if (webhookUrls != null) {
            this.webhookUrls.putAll(webhookUrls);
        }
        this.enabled = enabled;
        this.defaultUsername = Objects.requireNonNull(defaultUsername, "defaultUsername must not be null");
        this.defaultAvatarUrl = defaultAvatarUrl;
        this.rateLimitPerSecond = rateLimitPerSecond > 0 ? rateLimitPerSecond : DEFAULT_RATE_LIMIT;

        this.workerThread = new Thread(this::workerLoop, "uxm-discord-webhook-worker");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
    }

    public IslandDiscordWebhookService(DiscordWebhookClientPort clientPort, Map<DiscordTopic, String> webhookUrls) {
        this(clientPort, webhookUrls, true, DEFAULT_USERNAME, null, DEFAULT_RATE_LIMIT);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void dispatch(DiscordTopic topic, DiscordWebhookPayload payload) {
        Objects.requireNonNull(topic, "topic must not be null");
        Objects.requireNonNull(payload, "payload must not be null");

        if (!enabled) {
            return;
        }

        String url = webhookUrls.get(topic);
        if (url == null || url.isBlank()) {
            return;
        }

        // Apply defaults if absent
        String username = payload.username() != null ? payload.username() : defaultUsername;
        String avatar = payload.avatarUrl() != null ? payload.avatarUrl() : defaultAvatarUrl;
        DiscordWebhookPayload enriched =
                new DiscordWebhookPayload(username, avatar, payload.content(), payload.embeds());

        inFlight.incrementAndGet();
        if (!queue.offer(new WebhookTask(url, enriched))) {
            inFlight.decrementAndGet();
        }
    }

    public void notifyMilestone(
            IslandId islandId, String islandName, String leaderName, long level, List<String> members) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(islandName, "islandName must not be null");
        Objects.requireNonNull(leaderName, "leaderName must not be null");

        DiscordEmbedTexts.Milestone says = texts.milestone();
        // Locale.ROOT, so a level reads with the same digits on every server.
        String levelText = String.format(Locale.ROOT, "%,d", level);
        Map<String, String> values = Map.of("island", islandName, "leader", leaderName, "level", levelText);
        DiscordEmbed.Builder embedBuilder = DiscordEmbed.builder()
                .title(DiscordEmbedTexts.fill(says.title(), values))
                .description(DiscordEmbedTexts.fill(says.description(), values))
                .color(says.color())
                .addField(DiscordEmbedTexts.fill(says.leaderField(), values), leaderName, true)
                .addField(DiscordEmbedTexts.fill(says.levelField(), values), levelText, true)
                .timestamp(Instant.now())
                .footer(DiscordEmbedTexts.fill(says.footer(), values));

        if (members != null && !members.isEmpty()) {
            String memberList = String.join(", ", members);
            if (memberList.length() > 500) {
                memberList = memberList.substring(0, 497) + "...";
            }
            embedBuilder.addField(
                    DiscordEmbedTexts.fill(says.membersField(), Map.of("count", Integer.toString(members.size()))),
                    memberList,
                    false);
        }

        dispatch(DiscordTopic.MILESTONES, DiscordWebhookPayload.ofEmbed(embedBuilder.build()));
    }

    @Override
    public void notifyLeaderboard(String metricName, List<LeaderboardEntry> topEntries) {
        Objects.requireNonNull(metricName, "metricName must not be null");
        Objects.requireNonNull(topEntries, "topEntries must not be null");

        DiscordEmbedTexts.Leaderboard says = texts.leaderboard();
        StringBuilder sb = new StringBuilder();
        int max = Math.min(topEntries.size(), 10);
        for (int i = 0; i < max; i++) {
            LeaderboardEntry entry = topEntries.get(i);
            String place =
                    switch (entry.rank()) {
                        case 1 -> says.firstPlace();
                        case 2 -> says.secondPlace();
                        case 3 -> says.thirdPlace();
                        default ->
                            DiscordEmbedTexts.fill(says.otherPlace(), Map.of("rank", Integer.toString(entry.rank())));
                    };
            sb.append(DiscordEmbedTexts.fill(
                            says.line(),
                            Map.of("place", place, "island", entry.islandName(), "score", entry.formattedScore())))
                    .append("\n");
        }

        Map<String, String> values = Map.of("metric", metricName);
        DiscordEmbed embed = DiscordEmbed.builder()
                .title(DiscordEmbedTexts.fill(says.title(), values))
                .description(sb.length() > 0 ? sb.toString() : DiscordEmbedTexts.fill(says.empty(), values))
                .color(says.color())
                .timestamp(Instant.now())
                .footer(DiscordEmbedTexts.fill(says.footer(), values))
                .build();

        dispatch(DiscordTopic.LEADERBOARDS, DiscordWebhookPayload.ofEmbed(embed));
    }

    @Override
    public void notifyAlliance(String allianceName, String action, String actorName, String targetName) {
        Objects.requireNonNull(allianceName, "allianceName must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(actorName, "actorName must not be null");
        Objects.requireNonNull(targetName, "targetName must not be null");

        DiscordEmbedTexts.Alliance says = texts.alliance();
        Map<String, String> values =
                Map.of("alliance", allianceName, "action", action, "actor", actorName, "target", targetName);
        DiscordEmbed embed = DiscordEmbed.builder()
                .title(DiscordEmbedTexts.fill(says.title(), values))
                .description(DiscordEmbedTexts.fill(says.description(), values))
                .color(says.color())
                .addField(DiscordEmbedTexts.fill(says.allianceField(), values), allianceName, true)
                .addField(DiscordEmbedTexts.fill(says.actorField(), values), actorName, true)
                .addField(DiscordEmbedTexts.fill(says.targetField(), values), targetName, true)
                .timestamp(Instant.now())
                .footer(DiscordEmbedTexts.fill(says.footer(), values))
                .build();

        dispatch(DiscordTopic.ALLIANCES, DiscordWebhookPayload.ofEmbed(embed));
    }

    @Override
    public void notifyAdminAudit(String eventType, String severity, String description, Map<String, String> details) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(description, "description must not be null");

        DiscordEmbedTexts.Audit says = texts.audit();
        int color =
                switch (severity.toLowerCase(Locale.ROOT)) {
                    case "critical", "high" -> says.highColor();
                    case "medium", "warn" -> says.mediumColor();
                    default -> says.lowColor();
                };

        Map<String, String> values = Map.of("severity", severity.toUpperCase(Locale.ROOT), "event", eventType);
        DiscordEmbed.Builder builder = DiscordEmbed.builder()
                .title(DiscordEmbedTexts.fill(says.title(), values))
                .description(description)
                .color(color)
                .timestamp(Instant.now())
                .footer(DiscordEmbedTexts.fill(says.footer(), values));

        if (details != null) {
            for (Map.Entry<String, String> entry : details.entrySet()) {
                builder.addField(entry.getKey(), entry.getValue(), true);
            }
        }

        dispatch(DiscordTopic.ADMIN_AUDIT, DiscordWebhookPayload.ofEmbed(builder.build()));
    }

    public int queueSize() {
        return queue.size() + inFlight.get();
    }

    public void flushAndDrain(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while ((!queue.isEmpty() || inFlight.get() > 0) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private void workerLoop() {
        long minIntervalNanos = (long) (1_000_000_000.0 / rateLimitPerSecond);
        long lastDispatchNanos = 0;

        while (running.get() || !queue.isEmpty()) {
            try {
                WebhookTask task = queue.poll(100, TimeUnit.MILLISECONDS);
                if (task == null) {
                    continue;
                }

                // Rate limiting token delay
                long elapsed = System.nanoTime() - lastDispatchNanos;
                if (elapsed < minIntervalNanos) {
                    long sleepMs = (minIntervalNanos - elapsed) / 1_000_000L;
                    if (sleepMs > 0) {
                        Thread.sleep(sleepMs);
                    }
                }

                try {
                    clientPort.sendWebhook(task.url(), task.payload()).join();
                } catch (Exception expected) {
                    // Suppress network failures in worker thread to maintain system stability
                } finally {
                    inFlight.decrementAndGet();
                    lastDispatchNanos = System.nanoTime();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            workerThread.interrupt();
            try {
                workerThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

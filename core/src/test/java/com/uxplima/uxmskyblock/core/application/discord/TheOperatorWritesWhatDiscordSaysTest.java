package com.uxplima.uxmskyblock.core.application.discord;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.uxplima.uxmskyblock.core.domain.discord.DiscordEmbed;
import com.uxplima.uxmskyblock.core.domain.discord.DiscordEmbedField;
import com.uxplima.uxmskyblock.core.domain.discord.DiscordTopic;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every word a Discord embed says is the operator's.
 *
 * <p>The titles, descriptions, field names, footers and colours were English written into the
 * service, so a Turkish community read English and no server could put its own name in a footer.
 */
class TheOperatorWritesWhatDiscordSaysTest {

    private final List<DiscordEmbed> sent = new ArrayList<>();
    private final List<IslandDiscordWebhookService> opened = new ArrayList<>();

    private static DiscordEmbedTexts turkish() {
        return new DiscordEmbedTexts(
                new DiscordEmbedTexts.Milestone(
                        "Yeni seviye!",
                        "**<island>** adası <level>. seviyeye ulaştı.",
                        "Lider",
                        "Seviye",
                        "Üyeler (<count>)",
                        "Sunucumuz",
                        0x112233),
                new DiscordEmbedTexts.Leaderboard(
                        "En iyi adalar: <metric>",
                        "<place> <island> <score>",
                        "1.",
                        "2.",
                        "3.",
                        "<rank>.",
                        "Henüz kimse yok.",
                        "Sıralama",
                        0x445566),
                new DiscordEmbedTexts.Alliance(
                        "İttifak: <action>",
                        "<alliance> için bir gelişme.",
                        "İttifak",
                        "Başlatan",
                        "Hedef",
                        "Diplomasi",
                        0x778899),
                new DiscordEmbedTexts.Audit("Denetim [<severity>]: <event>", "Yönetim", 0xAA0000, 0xBB0000, 0xCC0000));
    }

    private IslandDiscordWebhookService serviceSaying(DiscordEmbedTexts texts) {
        DiscordWebhookClientPort client = (url, payload) -> {
            sent.addAll(payload.embeds());
            return CompletableFuture.completedFuture(true);
        };
        Map<DiscordTopic, String> urls = Map.of(
                DiscordTopic.MILESTONES, "https://m",
                DiscordTopic.LEADERBOARDS, "https://l",
                DiscordTopic.ALLIANCES, "https://a",
                DiscordTopic.ADMIN_AUDIT, "https://u");
        IslandDiscordWebhookService service =
                new IslandDiscordWebhookService(client, urls, true, "Bot", null, 100.0, texts);
        opened.add(service);
        return service;
    }

    @AfterEach
    void tearDown() {
        opened.forEach(IslandDiscordWebhookService::close);
    }

    @Test
    @DisplayName("A milestone says what the operator wrote, with the island's values in it")
    void aMilestoneSaysTheOperatorsWords() throws Exception {
        IslandDiscordWebhookService service = serviceSaying(turkish());
        service.notifyMilestone(IslandId.of(UUID.randomUUID()), "Atlantis", "Steve", 1000, List.of("Alex"));
        service.flushAndDrain(Duration.ofSeconds(2));

        DiscordEmbed embed = sent.getFirst();
        assertThat(embed.title()).isEqualTo("Yeni seviye!");
        assertThat(embed.description()).isEqualTo("**Atlantis** adası 1,000. seviyeye ulaştı.");
        assertThat(embed.color()).isEqualTo(0x112233);
        assertThat(embed.footer()).isEqualTo("Sunucumuz");
        assertThat(embed.fields()).extracting(DiscordEmbedField::name).containsExactly("Lider", "Seviye", "Üyeler (1)");
    }

    @Test
    @DisplayName("A leaderboard places the first three and numbers the rest the operator's way")
    void aLeaderboardUsesTheOperatorsPlaces() throws Exception {
        List<LeaderboardEntry> entries = new ArrayList<>();
        for (int rank = 1; rank <= 4; rank++) {
            entries.add(new LeaderboardEntry(rank, IslandId.of(UUID.randomUUID()), "I" + rank, rank, "s" + rank));
        }
        IslandDiscordWebhookService service = serviceSaying(turkish());
        service.notifyLeaderboard("Seviye", entries);
        service.flushAndDrain(Duration.ofSeconds(2));

        DiscordEmbed embed = sent.getFirst();
        assertThat(embed.title()).isEqualTo("En iyi adalar: Seviye");
        assertThat(embed.description()).isEqualTo("1. I1 s1\n2. I2 s2\n3. I3 s3\n4. I4 s4\n");
        assertThat(embed.color()).isEqualTo(0x445566);
    }

    @Test
    @DisplayName("An empty leaderboard says the operator's empty line")
    void anEmptyLeaderboardSaysTheOperatorsLine() throws Exception {
        IslandDiscordWebhookService service = serviceSaying(turkish());
        service.notifyLeaderboard("Seviye", List.of());
        service.flushAndDrain(Duration.ofSeconds(2));

        assertThat(sent.getFirst().description()).isEqualTo("Henüz kimse yok.");
    }

    @Test
    @DisplayName("An alliance and an audit read the operator's titles and colours")
    void allianceAndAuditReadTheOperatorsWords() throws Exception {
        IslandDiscordWebhookService service = serviceSaying(turkish());
        service.notifyAlliance("Demir", "Kuruldu", "Steve", "Alex");
        service.notifyAdminAudit("DUPE", "warn", "x", Map.of());
        service.flushAndDrain(Duration.ofSeconds(2));

        assertThat(sent).extracting(DiscordEmbed::title).containsExactly("İttifak: Kuruldu", "Denetim [WARN]: DUPE");
        assertThat(sent).extracting(DiscordEmbed::color).containsExactly(0x778899, 0xBB0000);
    }

    @Test
    @DisplayName("An island a player named after a placeholder keeps its name")
    void aNameIsNeverReadAsATemplate() throws Exception {
        IslandDiscordWebhookService service = serviceSaying(turkish());
        service.notifyMilestone(IslandId.of(UUID.randomUUID()), "<level>", "<island>", 7, List.of());
        service.flushAndDrain(Duration.ofSeconds(2));

        assertThat(sent.getFirst().description()).isEqualTo("**<level>** adası 7. seviyeye ulaştı.");
    }

    @Test
    @DisplayName("A level is written in ASCII digits on a server that writes its own")
    void aLevelIsAsciiEverywhere() throws Exception {
        IslandDiscordWebhookService service = serviceSaying(DiscordEmbedTexts.english());
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("ar-EG"));
        try {
            service.notifyMilestone(IslandId.of(UUID.randomUUID()), "Atlantis", "Steve", 1234, List.of());
        } finally {
            Locale.setDefault(before);
        }
        service.flushAndDrain(Duration.ofSeconds(2));

        assertThat(sent.getFirst().description()).contains("1,234");
    }
}

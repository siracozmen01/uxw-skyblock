package com.uxplima.uxmskyblock.core.domain.discord;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Immutable domain record representing an outbound Discord Webhook HTTP payload.
 * Provides zero-dependency, specification-compliant JSON serialization.
 */
public record DiscordWebhookPayload(
        @Nullable String username,
        @Nullable String avatarUrl,
        @Nullable String content,
        List<DiscordEmbed> embeds) {

    public DiscordWebhookPayload {
        embeds = embeds != null ? List.copyOf(embeds) : List.of();
    }

    public static DiscordWebhookPayload ofEmbed(DiscordEmbed embed) {
        return new DiscordWebhookPayload(null, null, null, List.of(embed));
    }

    public static DiscordWebhookPayload ofEmbeds(List<DiscordEmbed> embeds) {
        return new DiscordWebhookPayload(null, null, null, embeds);
    }

    public static DiscordWebhookPayload ofContent(String content) {
        return new DiscordWebhookPayload(null, null, content, List.of());
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;

        if (username != null && !username.isBlank()) {
            sb.append("\"username\":").append(quote(username));
            first = false;
        }

        if (avatarUrl != null && !avatarUrl.isBlank()) {
            if (!first) sb.append(",");
            sb.append("\"avatar_url\":").append(quote(avatarUrl));
            first = false;
        }

        if (content != null && !content.isBlank()) {
            if (!first) sb.append(",");
            sb.append("\"content\":").append(quote(content));
            first = false;
        }

        if (!embeds.isEmpty()) {
            if (!first) sb.append(",");
            sb.append("\"embeds\":[");
            for (int i = 0; i < embeds.size(); i++) {
                if (i > 0) sb.append(",");
                appendEmbed(sb, embeds.get(i));
            }
            sb.append("]");
        }

        sb.append("}");
        return sb.toString();
    }

    private static void appendEmbed(StringBuilder sb, DiscordEmbed embed) {
        sb.append("{");
        boolean first = true;

        if (embed.title() != null) {
            sb.append("\"title\":").append(quote(embed.title()));
            first = false;
        }

        if (embed.description() != null) {
            if (!first) sb.append(",");
            sb.append("\"description\":").append(quote(embed.description()));
            first = false;
        }

        if (embed.url() != null) {
            if (!first) sb.append(",");
            sb.append("\"url\":").append(quote(embed.url()));
            first = false;
        }

        if (embed.color() != 0) {
            if (!first) sb.append(",");
            sb.append("\"color\":").append(embed.color() & 0xFFFFFF);
            first = false;
        }

        if (embed.footer() != null) {
            if (!first) sb.append(",");
            sb.append("\"footer\":{\"text\":").append(quote(embed.footer())).append("}");
            first = false;
        }

        if (embed.timestamp() != null) {
            if (!first) sb.append(",");
            sb.append("\"timestamp\":").append(quote(embed.timestamp()));
            first = false;
        }

        if (embed.thumbnailUrl() != null) {
            if (!first) sb.append(",");
            sb.append("\"thumbnail\":{\"url\":")
                    .append(quote(embed.thumbnailUrl()))
                    .append("}");
            first = false;
        }

        if (embed.authorName() != null) {
            if (!first) sb.append(",");
            sb.append("\"author\":{\"name\":").append(quote(embed.authorName()));
            if (embed.authorIconUrl() != null) {
                sb.append(",\"icon_url\":").append(quote(embed.authorIconUrl()));
            }
            sb.append("}");
            first = false;
        }

        if (!embed.fields().isEmpty()) {
            if (!first) sb.append(",");
            sb.append("\"fields\":[");
            for (int i = 0; i < embed.fields().size(); i++) {
                if (i > 0) sb.append(",");
                DiscordEmbedField f = embed.fields().get(i);
                sb.append("{\"name\":")
                        .append(quote(f.name()))
                        .append(",\"value\":")
                        .append(quote(f.value()))
                        .append(",\"inline\":")
                        .append(f.inline())
                        .append("}");
            }
            sb.append("]");
        }

        sb.append("}");
    }

    private static String quote(String string) {
        if (string == null || string.isEmpty()) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(string.length() + 8);
        sb.append('"');
        for (int i = 0; i < string.length(); i++) {
            char c = string.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < ' ') {
                        String hex = "000" + Integer.toHexString(c);
                        sb.append("\\u").append(hex.substring(hex.length() - 4));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}

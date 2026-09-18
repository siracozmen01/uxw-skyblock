package com.uxplima.uxmskyblock.core.domain.discord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Immutable domain record representing a rich Discord embed card.
 */
public record DiscordEmbed(
        @Nullable String title,
        @Nullable String description,
        @Nullable String url,
        int color,
        @Nullable String footer,
        @Nullable String timestamp,
        @Nullable String thumbnailUrl,
        @Nullable String authorName,
        @Nullable String authorIconUrl,
        List<DiscordEmbedField> fields) {

    public DiscordEmbed {
        fields = fields != null ? List.copyOf(fields) : List.of();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private @Nullable String title;
        private @Nullable String description;
        private @Nullable String url;
        private int color = 0x5865F2; // Discord Blurple
        private @Nullable String footer;
        private @Nullable String timestamp;
        private @Nullable String thumbnailUrl;
        private @Nullable String authorName;
        private @Nullable String authorIconUrl;
        private final List<DiscordEmbedField> fields = new ArrayList<>();

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder color(int color) {
            this.color = color;
            return this;
        }

        public Builder footer(String footer) {
            this.footer = footer;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp != null ? timestamp.toString() : null;
            return this;
        }

        public Builder thumbnailUrl(String thumbnailUrl) {
            this.thumbnailUrl = thumbnailUrl;
            return this;
        }

        public Builder author(String authorName, @Nullable String authorIconUrl) {
            this.authorName = authorName;
            this.authorIconUrl = authorIconUrl;
            return this;
        }

        public Builder addField(String name, String value, boolean inline) {
            this.fields.add(new DiscordEmbedField(name, value, inline));
            return this;
        }

        public Builder addField(DiscordEmbedField field) {
            this.fields.add(Objects.requireNonNull(field, "field must not be null"));
            return this;
        }

        public DiscordEmbed build() {
            return new DiscordEmbed(
                    title, description, url, color, footer, timestamp, thumbnailUrl, authorName, authorIconUrl, fields);
        }
    }
}

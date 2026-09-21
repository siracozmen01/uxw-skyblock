package com.uxplima.uxmskyblock.bukkit.config;

import java.util.Locale;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Which language a player reads, and whether their own client decides it.
 *
 * <p>The number of languages is the number of files in {@code messages/}. Nothing here counts them
 * or names them beyond the fallback, so an operator who drops in {@code messages_de.conf} has a
 * German server without a release.
 */
public record LanguageConfiguration(String defaultLanguage, boolean followClient) {

    public static final String DEFAULT_LANGUAGE = "en";

    public LanguageConfiguration {
        Objects.requireNonNull(defaultLanguage, "defaultLanguage must not be null");
        if (defaultLanguage.isBlank()) {
            throw new IllegalArgumentException("language.default must not be blank");
        }
        defaultLanguage = defaultLanguage.trim().toLowerCase(Locale.ROOT);
    }

    /** The shipped shape: English underneath, and a player's own client language on top of it. */
    public static LanguageConfiguration defaults() {
        return new LanguageConfiguration(DEFAULT_LANGUAGE, true);
    }

    /**
     * Reads the {@code language} block, and answers {@link #defaults()} when a caller supplied the
     * configuration programmatically and there is no file to read.
     */
    public static LanguageConfiguration load(@Nullable ConfigurationNode rootNode) {
        if (rootNode == null) {
            return defaults();
        }
        ConfigurationNode language = rootNode.node("language");
        String configured = language.node("default").getString(DEFAULT_LANGUAGE);
        boolean followClient = language.node("follow-client").getBoolean(true);
        if (configured == null || configured.isBlank()) {
            configured = DEFAULT_LANGUAGE;
        }
        return new LanguageConfiguration(configured, followClient);
    }

    /** The fallback language as a {@link Locale}, which is what the locale seam speaks. */
    public Locale defaultLocale() {
        return Locale.of(defaultLanguage);
    }
}

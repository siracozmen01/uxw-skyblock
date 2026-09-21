package com.uxplima.uxmskyblock.bukkit.i18n;

import java.util.Locale;
import java.util.Objects;

import net.kyori.adventure.audience.Audience;

import com.uxplima.uxmlib.text.message.LocaleSource;

/**
 * Answers one language for every viewer, whatever their client is set to.
 *
 * <p>This is what {@code language.follow-client = false} selects. A server that runs in one
 * language wants every player to read that language, including the player whose client is English
 * because they bought the game that way.
 */
public record ServerLocaleSource(Locale defaultLocale) implements LocaleSource {

    public ServerLocaleSource {
        Objects.requireNonNull(defaultLocale, "defaultLocale must not be null");
    }

    @Override
    public Locale localeOf(Audience viewer) {
        Objects.requireNonNull(viewer, "viewer must not be null");
        return defaultLocale;
    }
}

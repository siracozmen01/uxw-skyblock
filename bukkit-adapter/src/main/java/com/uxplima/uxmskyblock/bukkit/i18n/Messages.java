package com.uxplima.uxmskyblock.bukkit.i18n;

import java.util.Locale;
import java.util.Objects;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmlib.text.message.LocaleSource;

/**
 * The single seam between an outcome of the game and the words a player reads about it.
 *
 * <p>A caller names a key and never a sentence. The viewer's own client language decides which
 * catalog answers, so two players in one island read the same event in two languages. The locale
 * resolution is uxmLib's {@link LocaleSource} rather than our own, because reading a client's
 * language setting is a platform mechanism and not part of the game this plugin plays.
 */
public final class Messages {

    private final MessageProvider provider;
    private final LocaleSource locales;

    public Messages(MessageProvider provider, LocaleSource locales) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.locales = Objects.requireNonNull(locales, "locales must not be null");
    }

    /** Renders {@code key} for {@code viewer} with the catalog prefix, as a chat line carries it. */
    public Component render(Audience viewer, String key, TagResolver... resolvers) {
        return provider.getComponent(key, languageOf(viewer), resolvers);
    }

    /**
     * Renders {@code key} for {@code viewer} without the catalog prefix, for the places a prefix
     * would be wrong: an item name, a lore line, a menu title, a form button.
     */
    public Component renderPlain(Audience viewer, String key, TagResolver... resolvers) {
        return provider.getComponentWithoutPrefix(key, languageOf(viewer), resolvers);
    }

    /** Sends {@code key} to {@code viewer} in the language that viewer reads. */
    public void send(Audience viewer, String key, TagResolver... resolvers) {
        Objects.requireNonNull(viewer, "viewer must not be null");
        viewer.sendMessage(render(viewer, key, resolvers));
    }

    /** Sends {@code key} to {@code viewer} without the catalog prefix. */
    public void sendPlain(Audience viewer, String key, TagResolver... resolvers) {
        Objects.requireNonNull(viewer, "viewer must not be null");
        viewer.sendMessage(renderPlain(viewer, key, resolvers));
    }

    /**
     * Answers whether the default catalog holds {@code key}. A key it does not hold reaches a
     * player as the key itself, so the guard test asks this rather than waiting for a screenshot.
     */
    public boolean has(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return provider.getKeys(provider.defaultLocale()).contains(key);
    }

    /** The catalogs this plugin ships, which is the list of languages it has. */
    public MessageProvider provider() {
        return provider;
    }

    private String languageOf(Audience viewer) {
        Objects.requireNonNull(viewer, "viewer must not be null");
        return locales.localeOf(viewer).getLanguage().toLowerCase(Locale.ROOT);
    }
}

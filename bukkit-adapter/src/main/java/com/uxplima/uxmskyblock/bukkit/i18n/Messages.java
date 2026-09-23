package com.uxplima.uxmskyblock.bukkit.i18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmlib.text.message.LocaleSource;
import com.uxplima.uxmlib.text.style.Styler;
import com.uxplima.uxmlib.text.style.Theme;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import org.jspecify.annotations.Nullable;

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

    /** The server's theme, which paints the roles and labels of a line an operator wrote. */
    private final Styler styler;

    public Messages(MessageProvider provider, LocaleSource locales) {
        this(provider, locales, Theme.defaults());
    }

    public Messages(MessageProvider provider, LocaleSource locales, Theme theme) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.locales = Objects.requireNonNull(locales, "locales must not be null");
        this.styler = new Styler(Objects.requireNonNull(theme, "theme must not be null"));
    }

    /**
     * Builds the seam the server is configured for: a player's own client language decides, unless
     * the operator turned {@code language.follow-client} off and asked for one language throughout.
     */
    public static Messages of(MessageProvider provider, LanguageConfiguration language) {
        return of(provider, language, Theme.defaults());
    }

    /** The same, painting an operator's lines with the server's own theme. */
    public static Messages of(MessageProvider provider, LanguageConfiguration language, Theme theme) {
        Objects.requireNonNull(language, "language must not be null");
        LocaleSource source = language.followClient()
                ? LocaleSource.ofDefault(language.defaultLocale())
                : new ServerLocaleSource(language.defaultLocale());
        return new Messages(provider, source, theme);
    }

    /**
     * The shipped catalogs with no operator file over them. This is what a test wants, and what a
     * caller that has no configuration to read can fall back to.
     */
    public static Messages bundled() {
        MessageProvider provider = new MessageProvider(LanguageConfiguration.DEFAULT_LANGUAGE);
        provider.loadBundledDefaults(Messages.class.getClassLoader());
        return of(provider, LanguageConfiguration.defaults());
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

    /**
     * The MiniMessage {@code key} holds for {@code viewer}, or {@code null} when no catalogue has it.
     *
     * <p>For a text that is drawn elsewhere, such as a part of an operator's effect line that names
     * its words by key.
     */
    public @Nullable String raw(Audience viewer, String key) {
        return has(key) ? provider.getRaw(key, languageOf(viewer)) : null;
    }

    /**
     * A line an operator wrote, with the theme's roles and labels painted for the language
     * {@code viewer} reads. Every other letter is left as written, so a placeholder in it still
     * fills in.
     */
    public String paint(Audience viewer, String line) {
        Objects.requireNonNull(line, "line must not be null");
        return styler.tokens(line, Locale.forLanguageTag(languageOf(viewer)));
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
     * Renders a list valued key, such as the lines of a help screen, in the viewer's language. The
     * lines carry no prefix: a screen of them would repeat it on every row.
     */
    public List<Component> renderAll(Audience viewer, String key, TagResolver... resolvers) {
        String language = languageOf(viewer);
        List<String> templates = provider.getRawList(key, language);
        List<Component> rendered = new ArrayList<>(templates.size());
        for (String template : templates) {
            rendered.add(provider.renderTemplate(template, resolvers));
        }
        return List.copyOf(rendered);
    }

    /** Sends every line of a list valued key to {@code viewer}, in the order the file holds them. */
    public void sendAll(Audience viewer, String key, TagResolver... resolvers) {
        Objects.requireNonNull(viewer, "viewer must not be null");
        for (Component line : renderAll(viewer, key, resolvers)) {
            viewer.sendMessage(line);
        }
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
        Locale locale = locales.localeOf(viewer);
        if (locale == null) {
            return provider.defaultLocale();
        }
        return locale.getLanguage().toLowerCase(Locale.ROOT);
    }
}

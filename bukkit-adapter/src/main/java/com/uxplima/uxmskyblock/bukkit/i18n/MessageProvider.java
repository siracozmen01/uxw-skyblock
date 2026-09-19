package com.uxplima.uxmskyblock.bukkit.i18n;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * Enterprise thread-safe localization provider supporting multi-locale HOCON catalogs
 * and Adventure MiniMessage rich component rendering with TagResolver placeholders.
 */
public final class MessageProvider {

    private static final Logger LOGGER = Logger.getLogger(MessageProvider.class.getName());
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final String DEFAULT_LOCALE = "en";

    private final String defaultLocale;
    private final Map<String, Map<String, String>> localeCatalogs = new ConcurrentHashMap<>();
    private final Map<String, String> prefixes = new ConcurrentHashMap<>();

    public MessageProvider() {
        this(DEFAULT_LOCALE);
    }

    public MessageProvider(String defaultLocale) {
        this.defaultLocale = (defaultLocale != null && !defaultLocale.isBlank())
                ? defaultLocale.toLowerCase(Locale.ROOT)
                : DEFAULT_LOCALE;
    }

    /**
     * Loads a locale catalog from an input stream containing HOCON formatted messages.
     *
     * @param locale the target locale code (e.g. "en", "tr")
     * @param in the input stream containing HOCON configuration
     * @throws IOException if an I/O error occurs
     */
    public void loadFromStream(String locale, InputStream in) throws IOException {
        Objects.requireNonNull(locale, "locale must not be null");
        Objects.requireNonNull(in, "in must not be null");

        String normalizedLocale = locale.toLowerCase(Locale.ROOT);
        try (Reader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            HoconConfigurationLoader loader = HoconConfigurationLoader.builder()
                    .source(() -> new BufferedReader(reader))
                    .build();
            CommentedConfigurationNode root = loader.load();
            loadNode(normalizedLocale, root);
        }
    }

    /**
     * Loads a locale catalog from a file path.
     *
     * @param locale the target locale code
     * @param path the file path on disk
     * @throws IOException if an I/O error occurs
     */
    public void loadFromFile(String locale, Path path) throws IOException {
        Objects.requireNonNull(locale, "locale must not be null");
        Objects.requireNonNull(path, "path must not be null");

        if (!Files.exists(path)) {
            LOGGER.warning(() -> "Localization file does not exist: " + path);
            return;
        }

        try (InputStream in = Files.newInputStream(path)) {
            loadFromStream(locale, in);
        }
    }

    /**
     * Loads bundled resources for known locales ("en", "tr") using the specified ClassLoader.
     *
     * @param classLoader the classloader to search for /messages/messages_{locale}.conf
     */
    public void loadBundledDefaults(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader must not be null");
        for (String lang : new String[] {"en", "tr"}) {
            String resourcePath = "messages/messages_" + lang + ".conf";
            try (InputStream in = classLoader.getResourceAsStream(resourcePath)) {
                if (in != null) {
                    loadFromStream(lang, in);
                    LOGGER.info(() -> "Loaded bundled localization catalog for locale: " + lang);
                } else {
                    LOGGER.warning(() -> "Bundled localization catalog not found: " + resourcePath);
                }
            } catch (IOException e) {
                LOGGER.warning(() -> "Failed to load bundled localization for " + lang + ": " + e.getMessage());
            }
        }
    }

    private void loadNode(String locale, ConfigurationNode root) {
        Map<String, String> catalog = new HashMap<>();
        String prefix = root.node("prefix").getString("");
        prefixes.put(locale, prefix);

        flattenNode("", root, catalog);
        localeCatalogs.put(locale, Collections.unmodifiableMap(catalog));
    }

    private void flattenNode(String currentPath, ConfigurationNode node, Map<String, String> out) {
        if (node.isMap()) {
            for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                    node.childrenMap().entrySet()) {
                String key = String.valueOf(entry.getKey());
                String nextPath = currentPath.isEmpty() ? key : currentPath + "." + key;
                flattenNode(nextPath, entry.getValue(), out);
            }
        } else {
            String value = node.getString();
            if (value != null) {
                out.put(currentPath, value);
            }
        }
    }

    /**
     * Retrieves the raw template string for the given key and locale, falling back to default locale.
     *
     * @param key the dotted message key (e.g. "command.create_success")
     * @param locale the requested locale, or null for default
     * @return raw MiniMessage template string, or the key itself if missing
     */
    public String getRaw(String key, String locale) {
        Objects.requireNonNull(key, "key must not be null");
        String targetLocale = (locale != null && !locale.isBlank()) ? locale.toLowerCase(Locale.ROOT) : defaultLocale;

        Map<String, String> catalog = localeCatalogs.get(targetLocale);
        if (catalog != null && catalog.containsKey(key)) {
            return catalog.get(key);
        }

        // Fallback to default locale
        if (!targetLocale.equals(defaultLocale)) {
            Map<String, String> defaultCatalog = localeCatalogs.get(defaultLocale);
            if (defaultCatalog != null && defaultCatalog.containsKey(key)) {
                return defaultCatalog.get(key);
            }
        }

        return key;
    }

    /**
     * Renders a message key as an Adventure Component with prefix and MiniMessage formatting.
     *
     * @param key message key
     * @param locale requested locale code
     * @param resolvers optional TagResolvers for placeholders
     * @return parsed Adventure Component
     */
    public Component getComponent(String key, String locale, TagResolver... resolvers) {
        String template = getRaw(key, locale);
        String targetLocale = (locale != null && !locale.isBlank()) ? locale.toLowerCase(Locale.ROOT) : defaultLocale;

        String prefix = prefixes.getOrDefault(targetLocale, prefixes.getOrDefault(defaultLocale, ""));
        String fullMessage = template.equals(key) ? template : prefix + template;

        if (resolvers.length == 0) {
            return MINI_MESSAGE.deserialize(fullMessage);
        }
        return MINI_MESSAGE.deserialize(fullMessage, TagResolver.resolver(resolvers));
    }

    /**
     * Renders a message key without prefix as an Adventure Component.
     */
    public Component getComponentWithoutPrefix(String key, String locale, TagResolver... resolvers) {
        String template = getRaw(key, locale);
        if (resolvers.length == 0) {
            return MINI_MESSAGE.deserialize(template);
        }
        return MINI_MESSAGE.deserialize(template, TagResolver.resolver(resolvers));
    }

    public Set<String> getAvailableLocales() {
        return Collections.unmodifiableSet(localeCatalogs.keySet());
    }

    public Set<String> getKeys(String locale) {
        Map<String, String> catalog = localeCatalogs.get(locale.toLowerCase(Locale.ROOT));
        return catalog != null ? catalog.keySet() : Collections.emptySet();
    }

    public String defaultLocale() {
        return defaultLocale;
    }
}

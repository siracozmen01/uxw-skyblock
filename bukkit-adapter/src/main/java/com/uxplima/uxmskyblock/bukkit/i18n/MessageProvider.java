package com.uxplima.uxmskyblock.bukkit.i18n;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmlib.text.language.LibraryWords;
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
    private final Map<String, Map<String, List<String>>> localeLists = new ConcurrentHashMap<>();
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

    /** The bundled catalogue file names, from which the language tag is read. */
    private static final Pattern BUNDLED_CATALOG = Pattern.compile("messages_([A-Za-z0-9_-]+)\\.conf");

    /**
     * Loads every bundled message catalogue, whatever languages the build happens to ship.
     *
     * <p>This used to name two: English and Turkish. The number of languages a plugin speaks is the
     * number of files in {@code messages/}, and nothing counts them. A build that ships a third
     * catalogue got nothing from it, and the only sign was a player reading English.
     *
     * @param classLoader the classloader holding the bundled {@code messages/} folder
     */
    public void loadBundledDefaults(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader must not be null");

        Set<String> locales = bundledLocales(classLoader);
        if (locales.isEmpty()) {
            LOGGER.warning("No bundled localization catalog was found at all.");
            return;
        }
        for (String lang : locales) {
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
        underlayLibraryWords();
    }

    /**
     * Puts the words uxmLib ships for its own windows under every language this plugin speaks.
     *
     * <p>The library's windows ask the plugin's catalogue for {@code gui.*} keys: the note a cancelled
     * prompt sends, the page arrows, the confirm buttons. This catalogue never had them, so a player
     * who cancelled a prompt read {@code gui.input.cancelled} in chat. The library carries the words
     * in its jar; they go in under this plugin's own lines, which still win key by key, as does an
     * operator's file read after this.
     */
    private void underlayLibraryWords() {
        Map<String, Map<String, String>> library = new HashMap<>();
        for (Map.Entry<Locale, Map<String, String>> language :
                LibraryWords.shipped().entrySet()) {
            library.put(language.getKey().getLanguage().toLowerCase(Locale.ROOT), language.getValue());
        }
        for (String locale : Set.copyOf(localeCatalogs.keySet())) {
            Map<String, String> words = library.get(locale);
            if (words == null) {
                continue;
            }
            Map<String, String> catalog = new HashMap<>(localeCatalogs.get(locale));
            words.forEach(catalog::putIfAbsent);
            localeCatalogs.put(locale, Collections.unmodifiableMap(catalog));
        }
    }

    /**
     * The language tags the bundled {@code messages/} folder holds.
     *
     * <p>Reads the folder rather than a list: from inside the jar it is a jar entry walk, and from
     * an exploded build directory it is a directory listing. Either way the answer is the files.
     */
    static Set<String> bundledLocales(ClassLoader classLoader) {
        Set<String> locales = new java.util.TreeSet<>();
        try {
            java.util.Enumeration<java.net.URL> roots = classLoader.getResources("messages");
            while (roots.hasMoreElements()) {
                java.net.URL root = roots.nextElement();
                if ("jar".equals(root.getProtocol())) {
                    collectFromJar(root, locales);
                } else if ("file".equals(root.getProtocol())) {
                    collectFromDirectory(root, locales);
                }
            }
        } catch (IOException e) {
            LOGGER.warning(() -> "The bundled messages folder could not be read: " + e.getMessage());
        }
        return locales;
    }

    private static void collectFromJar(java.net.URL root, Set<String> locales) {
        try {
            java.net.URLConnection connection = root.openConnection();
            if (!(connection instanceof java.net.JarURLConnection jarConnection)) {
                return;
            }
            try (java.util.jar.JarFile jar = jarConnection.getJarFile()) {
                java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.startsWith("messages/")) {
                        continue;
                    }
                    addLocale(name.substring("messages/".length()), locales);
                }
            }
        } catch (IOException e) {
            LOGGER.warning(() -> "The bundled messages jar could not be read: " + e.getMessage());
        }
    }

    private static void collectFromDirectory(java.net.URL root, Set<String> locales) {
        try {
            Path directory = Path.of(root.toURI());
            if (!Files.isDirectory(directory)) {
                return;
            }
            try (java.util.stream.Stream<Path> files = Files.list(directory)) {
                files.forEach(file -> addLocale(file.getFileName().toString(), locales));
            }
        } catch (IOException | java.net.URISyntaxException e) {
            LOGGER.warning(() -> "The bundled messages folder could not be listed: " + e.getMessage());
        }
    }

    private static void addLocale(String fileName, Set<String> locales) {
        Matcher matcher = BUNDLED_CATALOG.matcher(fileName);
        if (matcher.matches()) {
            locales.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Reads one catalogue over whatever this locale already holds.
     *
     * <p>It used to replace. An operator's file is loaded after the bundled one, so replacing meant
     * their file had to carry every key in the plugin or the rest rendered as their own key names.
     * That is certain to bite on an update: the jar gains a key, the operator's file predates it,
     * and every player reads {@code error.players_only} where a sentence belongs. The operator's
     * line wins for the keys they wrote, and every other key keeps the answer it had.
     */
    private void loadNode(String locale, ConfigurationNode root) {
        Map<String, String> catalog = new HashMap<>(localeCatalogs.getOrDefault(locale, Map.of()));
        Map<String, List<String>> lists = new HashMap<>(localeLists.getOrDefault(locale, Map.of()));

        // A file with no prefix of its own keeps the one it had, for the same reason.
        String prefix = root.node("prefix").getString();
        if (prefix != null && !prefix.isEmpty()) {
            prefixes.put(locale, prefix);
        } else {
            prefixes.putIfAbsent(locale, "");
        }

        flattenNode("", root, catalog, lists);
        localeCatalogs.put(locale, Collections.unmodifiableMap(catalog));
        localeLists.put(locale, Collections.unmodifiableMap(lists));
    }

    private void flattenNode(
            String currentPath, ConfigurationNode node, Map<String, String> out, Map<String, List<String>> lists) {
        if (node.isMap()) {
            for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                    node.childrenMap().entrySet()) {
                String key = String.valueOf(entry.getKey());
                String nextPath = currentPath.isEmpty() ? key : currentPath + "." + key;
                flattenNode(nextPath, entry.getValue(), out, lists);
            }
        } else if (node.isList()) {
            List<String> values = new ArrayList<>();
            for (ConfigurationNode child : node.childrenList()) {
                String value = child.getString();
                if (value != null) {
                    values.add(value);
                }
            }
            lists.put(currentPath, List.copyOf(values));
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

    /**
     * Retrieves a list valued entry, such as the lines of a help screen, falling back to the default
     * locale. A list is the shape an operator can add a line to without waiting for a release.
     *
     * @param key the dotted message key
     * @param locale the requested locale, or null for default
     * @return the raw MiniMessage templates in file order, empty when the key holds no list
     */
    public List<String> getRawList(String key, String locale) {
        Objects.requireNonNull(key, "key must not be null");
        String targetLocale = (locale != null && !locale.isBlank()) ? locale.toLowerCase(Locale.ROOT) : defaultLocale;

        Map<String, List<String>> lists = localeLists.get(targetLocale);
        if (lists != null && lists.containsKey(key)) {
            return lists.get(key);
        }

        if (!targetLocale.equals(defaultLocale)) {
            Map<String, List<String>> defaultLists = localeLists.get(defaultLocale);
            if (defaultLists != null && defaultLists.containsKey(key)) {
                return defaultLists.get(key);
            }
        }

        return List.of();
    }

    /**
     * Renders a raw MiniMessage template that a caller already took out of a catalog, used by the
     * list valued entries where the key names many templates rather than one.
     */
    public Component renderTemplate(String template, TagResolver... resolvers) {
        Objects.requireNonNull(template, "template must not be null");
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

    /** The list valued keys a locale holds, which {@link #getKeys(String)} does not report. */
    public Set<String> getListKeys(String locale) {
        Map<String, List<String>> lists = localeLists.get(locale.toLowerCase(Locale.ROOT));
        return lists != null ? lists.keySet() : Collections.emptySet();
    }

    public String defaultLocale() {
        return defaultLocale;
    }
}

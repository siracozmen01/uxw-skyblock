package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.plugin.java.JavaPlugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A key a release adds reaches the server that already runs the plugin, and a key the operator took
 * out stays out.
 *
 * <p>The contract names this test. The boot wrote each shipped file when it was absent and never
 * looked at it again, so an operator who had run the plugin since an early release never got a key a
 * later one added. The boot now merges each shipped file into the operator's, keeps the file as it
 * was as {@code .bak}, and keeps what it shipped under {@code .defaults/} so the next release knows
 * which keys are new and which the operator deleted.
 */
class ConfigReachesAnOldServerTest {

    private static final ClassLoader JAR = ConfigReachesAnOldServerTest.class.getClassLoader();

    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path dataDir;

    @Test
    @DisplayName("The shipped config.conf gives an old server the key it lacks and changes no value the operator wrote")
    void anOldServerGetsTheNewKey() throws Exception {
        Path file = dataDir.resolve("config.conf");
        String old = shipped("config.conf")
                .replace("world-name = \"world\"", "world-name = \"islands\"")
                .replace("clustered = false", "");
        Files.writeString(file, old);
        assertThat(read(file).node("server-node", "clustered").virtual()).isTrue();

        boolean wrote = PluginSettings.bringUpToDate(dataDir, file, "config.conf", JAR);

        assertThat(wrote).isTrue();
        ConfigurationNode now = read(file);
        assertThat(now.node("server-node", "clustered").getBoolean(true)).isFalse();
        assertThat(now.node("server-node", "world-name").getString()).isEqualTo("islands");
        assertThat(Files.readString(dataDir.resolve("config.conf.bak"))).isEqualTo(old);
        assertThat(dataDir.resolve(".defaults/config.conf")).hasContent(shipped("config.conf"));
    }

    @Test
    @DisplayName("Every shipped module file is merged against itself without writing a thing")
    void anUpToDateServerIsLeftAlone() throws Exception {
        for (String resource : new String[] {"config.conf", "modules.conf", "commands.conf", "modules/missions.conf"}) {
            Path file = dataDir.resolve(resource);
            Files.createDirectories(file.getParent());
            Files.writeString(file, shipped(resource));

            assertThat(PluginSettings.bringUpToDate(dataDir, file, resource, JAR))
                    .describedAs(resource)
                    .isFalse();
            assertThat(file.resolveSibling(file.getFileName() + ".bak")).doesNotExist();
            assertThat(dataDir.resolve(".defaults").resolve(resource)).exists();
        }
    }

    @Test
    @DisplayName("A key the last release shipped and the operator deleted is not brought back")
    void aDeletedKeyStaysDeleted() throws Exception {
        Path jar = release("missions { daily { goal = 10 }, weekly { goal = 70 } }");
        Path file = dataDir.resolve("missions.conf");
        Files.writeString(file, "missions { daily { goal = 10 }, weekly { goal = 70 } }");
        try (URLClassLoader first = loader(jar)) {
            PluginSettings.bringUpToDate(dataDir, file, "missions.conf", first);
        }
        Files.writeString(file, "missions { daily { goal = 12 } }");

        try (URLClassLoader same = loader(jar)) {
            assertThat(PluginSettings.bringUpToDate(dataDir, file, "missions.conf", same))
                    .isFalse();
        }
        assertThat(read(file).node("missions", "weekly").virtual()).isTrue();
        assertThat(read(file).node("missions", "daily", "goal").getInt()).isEqualTo(12);
    }

    @Test
    @DisplayName("A key new to this release is added, but never under a section the operator took out")
    void aNewKeyComesButNotHalfASection() throws Exception {
        Path file = dataDir.resolve("missions.conf");
        Files.writeString(file, "missions { daily { goal = 10 }, weekly { goal = 70 } }");
        try (URLClassLoader first = loader(release("missions { daily { goal = 10 }, weekly { goal = 70 } }"))) {
            PluginSettings.bringUpToDate(dataDir, file, "missions.conf", first);
        }
        Files.writeString(file, "missions { daily { goal = 12 } }");

        try (URLClassLoader next = loader(release(
                "missions { daily { goal = 10, icon = clock }, weekly { goal = 70, icon = map } }, streaks = true"))) {
            assertThat(PluginSettings.bringUpToDate(dataDir, file, "missions.conf", next))
                    .isTrue();
        }
        ConfigurationNode now = read(file);
        assertThat(now.node("missions", "daily", "icon").getString()).isEqualTo("clock");
        assertThat(now.node("missions", "daily", "goal").getInt()).isEqualTo(12);
        assertThat(now.node("missions", "weekly").virtual())
                .describedAs("the weekly mission the operator deleted does not come back as an icon alone")
                .isTrue();
        assertThat(now.node("streaks").getBoolean()).isTrue();
    }

    @Test
    @DisplayName("A section new to this release keeps every value the operator already wrote in it, empty ones too")
    void aNewSectionKeepsWhatTheOperatorWrote() throws Exception {
        Path file = dataDir.resolve("missions.conf");
        Files.writeString(file, "enabled = true");
        try (URLClassLoader first = loader(release("enabled = true"))) {
            PluginSettings.bringUpToDate(dataDir, file, "missions.conf", first);
        }
        Files.writeString(file, "enabled = true\nmodes { keep = \"\", build = SURVIVAL, list = [] }\nstreak = off");

        try (URLClassLoader next = loader(release(
                "enabled = true\nmodes { keep = \"a.permission\", build = CREATIVE, visit = ADVENTURE, list = [a] }\n"
                        + "streak { days = 3 }"))) {
            assertThat(PluginSettings.bringUpToDate(dataDir, file, "missions.conf", next))
                    .isTrue();
        }
        ConfigurationNode now = read(file);
        assertThat(now.node("modes", "visit").getString()).isEqualTo("ADVENTURE");
        assertThat(now.node("modes", "build").getString()).isEqualTo("SURVIVAL");
        assertThat(now.node("modes", "keep").getString())
                .describedAs("an empty value is a value the operator wrote")
                .isEmpty();
        assertThat(now.node("modes", "list").childrenList())
                .describedAs("an empty list is a list the operator wrote")
                .isEmpty();
        assertThat(now.node("streak").getString())
                .describedAs("a value where the release has a section stays the operator's value")
                .isEqualTo("off");
    }

    @Test
    @DisplayName("A key the release adds is not taken for what a key the file already has was meant to be")
    void aNewKeyMakesNoKeyAMisspelling() throws Exception {
        Path file = dataDir.resolve("commands.conf");
        Files.writeString(file, "verbs { rate = rate, trust = trust }");
        try (URLClassLoader first = loader(release("verbs { rate = rate, trust = trust }"))) {
            PluginSettings.bringUpToDate(dataDir, file, "missions.conf", first);
        }
        java.util.List<String> warned = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Logger merge = java.util.logging.Logger.getLogger("com.uxplima.uxmlib.config.HoconConfig");
        java.util.logging.Handler listening = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                warned.add(String.valueOf(record.getMessage()));
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        merge.addHandler(listening);
        try (URLClassLoader next = loader(release("verbs { rate = rate, trade = trade, trust = trust }"))) {
            assertThat(PluginSettings.bringUpToDate(dataDir, file, "missions.conf", next))
                    .isTrue();
        } finally {
            merge.removeHandler(listening);
        }

        assertThat(read(file).node("verbs", "trade").getString()).isEqualTo("trade");
        assertThat(warned)
                .describedAs("rate is a key the release ships, not a misspelling of trade")
                .noneMatch(line -> line.contains("Did you mean"));
    }

    @Test
    @DisplayName("A file nobody edited becomes what the new release ships, comments and order included")
    void anUntouchedFileTakesTheNewRelease() throws Exception {
        Path file = dataDir.resolve("missions.conf");
        String first = "# the first words\nmissions { daily { goal = 10 } }\n";
        String second = "# the second words\nmissions {\n  daily { goal = 20 }\n}\n";
        Files.writeString(file, first);
        try (URLClassLoader release = loader(release(first))) {
            PluginSettings.bringUpToDate(dataDir, file, "missions.conf", release);
        }

        try (URLClassLoader next = loader(release(second))) {
            assertThat(PluginSettings.bringUpToDate(dataDir, file, "missions.conf", next))
                    .isTrue();
        }
        assertThat(file).hasContent(second);
        assertThat(dataDir.resolve("missions.conf.bak")).hasContent(first);
    }

    @Test
    @DisplayName("A value the operator never changed takes the new release's, and one they changed stays theirs")
    void anUntouchedValueTakesTheNewRelease() throws Exception {
        Path file = dataDir.resolve("missions.conf");
        String first = "title = \"<red>Missions\", daily { goal = 10, icon = clock }, weekly { goal = 70 }, "
                + "note = \"old\", good = \"<aqua>wheat\"";
        Files.writeString(file, first);
        try (URLClassLoader release = loader(release(first))) {
            PluginSettings.bringUpToDate(dataDir, file, "missions.conf", release);
        }
        Files.writeString(file, first.replace("goal = 70", "goal = 75").replace("note = \"old\"", "note = \"mine\""));

        try (URLClassLoader next =
                loader(release("title = \"<accent>Missions\", daily { goal = 20, icon = clock }, weekly { goal = 80 }, "
                        + "good { title = \"<item>\" }"))) {
            assertThat(PluginSettings.bringUpToDate(dataDir, file, "missions.conf", next))
                    .isTrue();
        }
        ConfigurationNode now = read(file);
        assertThat(now.node("title").getString()).isEqualTo("<accent>Missions");
        assertThat(now.node("daily", "goal").getInt()).isEqualTo(20);
        assertThat(now.node("weekly", "goal").getInt())
                .describedAs("the operator wrote 75, so 75 it stays")
                .isEqualTo(75);
        assertThat(now.node("note").getString())
                .describedAs("a key the release dropped stays when the operator wrote it")
                .isEqualTo("mine");
        assertThat(now.node("good", "title").getString())
                .describedAs("a line that became a block becomes the block")
                .isEqualTo("<item>");
        assertThat(Files.readString(dataDir.resolve("missions.conf.bak"))).contains("goal = 75");
    }

    @Test
    @DisplayName("A key the operator never touched and the release dropped goes with it")
    void anUntouchedDroppedKeyGoes() throws Exception {
        Path file = dataDir.resolve("missions.conf");
        Files.writeString(file, "keep = 1, gone = 2");
        try (URLClassLoader release = loader(release("keep = 1, gone = 2"))) {
            PluginSettings.bringUpToDate(dataDir, file, "missions.conf", release);
        }
        Files.writeString(file, "keep = 5, gone = 2");

        try (URLClassLoader next = loader(release("keep = 1"))) {
            assertThat(PluginSettings.bringUpToDate(dataDir, file, "missions.conf", next))
                    .isTrue();
        }
        assertThat(read(file).node("gone").virtual()).isTrue();
        assertThat(read(file).node("keep").getInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("The boot brings the operator's files up to date before it reads them")
    void theBootMakesTheCall() throws Exception {
        Path config = dataDir.resolve("config.conf");
        Files.writeString(config, shipped("config.conf").replace("clustered = false", ""));
        Path missions = dataDir.resolve("modules/missions.conf");
        Files.createDirectories(missions.getParent());
        Files.writeString(missions, "");
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dataDir.toFile());
        when(plugin.getResource(anyString())).thenAnswer(call -> JAR.getResourceAsStream(call.getArgument(0)));

        ConfigurationLoader.loadAndValidate(plugin);

        assertThat(read(config).node("server-node", "clustered").virtual()).isFalse();
        assertThat(read(missions).childrenMap()).isNotEmpty();
        assertThat(dataDir.resolve(".defaults/commands.conf")).exists();
        assertThat(dataDir.resolve(".defaults/messages/messages_en.conf"))
                .describedAs("a catalogue is brought up to date too, so the next release can restyle it")
                .exists();
        assertThat(dataDir.resolve(".defaults/menus/island-main.conf"))
                .describedAs("and so is a menu")
                .exists();
    }

    private Path release(String missions) throws Exception {
        Path jar = Files.createTempDirectory(dataDir, "release");
        Files.writeString(jar.resolve("missions.conf"), missions);
        return jar;
    }

    private static URLClassLoader loader(Path jar) throws Exception {
        return new URLClassLoader(new URL[] {jar.toUri().toURL()}, null);
    }

    private static String shipped(String resource) throws Exception {
        try (var in = JAR.getResourceAsStream(resource)) {
            assertThat(in).describedAs(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ConfigurationNode read(Path file) throws Exception {
        return HoconConfigurationLoader.builder().path(file).build().load();
    }
}

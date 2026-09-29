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

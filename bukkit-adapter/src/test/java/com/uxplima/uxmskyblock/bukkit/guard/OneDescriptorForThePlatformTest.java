package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The plugin describes itself once, for the server it runs on.
 *
 * <p>A plugin.yml was shipped beside the paper-plugin.yml. Paper reads only the second, so the first
 * drifted on its own: it never learned mcMMO had to load first, and both said api-version 1.21 on a
 * 26.2 server. The version the descriptor names is read off the version catalogue, so moving the
 * platform without moving it fails here.
 */
class OneDescriptorForThePlatformTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");

    @Test
    @DisplayName("Only paper-plugin.yml describes the plugin")
    void onlyOneDescriptor() {
        assertThat(RESOURCES.resolve("paper-plugin.yml")).exists();
        assertThat(RESOURCES.resolve("plugin.yml"))
                .describedAs("a second descriptor Paper never reads")
                .doesNotExist();
    }

    @Test
    @DisplayName("The api-version is the platform's own version, read off the catalogue")
    void theApiVersionIsThePlatforms() throws IOException {
        String catalogue = Files.readString(Path.of("..", "gradle", "libs.versions.toml"));
        Matcher paper = Pattern.compile("(?m)^paper\\s*=\\s*\"(\\d+\\.\\d+)").matcher(catalogue);
        assertThat(paper.find())
                .describedAs("the catalogue names a paper version")
                .isTrue();

        String descriptor = Files.readString(RESOURCES.resolve("paper-plugin.yml"));
        assertThat(descriptor).contains("api-version: '" + paper.group(1) + "'");
    }
}

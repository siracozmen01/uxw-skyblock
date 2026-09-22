package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What an interaction fires is the operator's list, not two lines of Java.
 *
 * <p>Every interaction in this plugin fires any number of messages, titles, subtitles, action bars,
 * boss bars, sounds and effects, and the operator writes them. Four interactions had a sound
 * written into the source instead and two of those a particle as well, so a server that wanted a
 * different note, or none, or a title beside it, had nowhere to say so.
 *
 * <p>The applier is the one place allowed to name a sound or a particle, because naming them is
 * what it does: it turns the operator's word into the server's own.
 */
class NoEffectIsWrittenIntoJavaTest {

    private static final Path SOURCES = Path.of("src/main/java");

    /**
     * Where an effect may still be named in Java, with the reason it may.
     *
     * <p>The island edge is not an interaction. It is a continuous outline drawn while a player
     * stands near it, with its own colour and its own switch, and a list of effects is the wrong
     * shape for a thing that is drawn every tick rather than fired once.
     */
    private static final List<String> ALLOWED =
            List.of("effect/InteractionEffectPlayer.java", "boundary/IslandBoundaryListener.java");

    @Test
    @DisplayName("No sound and no particle is named outside the applier")
    void noeffectIsNamedInJava() throws IOException {
        TreeSet<String> offences = new TreeSet<>();

        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String path = file.toString().replace('\\', '/');
                if (ALLOWED.stream().anyMatch(path::endsWith)) {
                    continue;
                }
                String body = Files.readString(file, StandardCharsets.UTF_8);
                if (body.contains(".playSound(") || body.contains(".spawnParticle(")) {
                    offences.add(file.getFileName().toString());
                }
            }
        }

        assertThat(offences)
                .describedAs("an interaction fires what the operator wrote. A sound or a particle "
                        + "named here is one they cannot change, cannot add to and cannot turn off")
                .isEmpty();
    }

    @Test
    @DisplayName("The applier really does name them, so an empty scan cannot pass this file")
    void theapplierNamesThem() throws IOException {
        String applier = Files.readString(
                SOURCES.resolve("com/uxplima/uxmskyblock/bukkit/effect/InteractionEffectPlayer.java"),
                StandardCharsets.UTF_8);

        assertThat(applier).contains(".playSound(").contains(".spawnParticle(");
    }
}

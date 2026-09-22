package com.uxplima.uxmskyblock.bukkit.bedrock;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.uxplima.uxmlib.bedrock.BedrockDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bedrock players are recognised once floodgate enables, whenever that is.
 *
 * <p>The detector was chosen once, while the menus were built. A floodgate that enabled after that
 * left it on the detector that answers no for everybody, and Bedrock players got Java windows.
 */
class BedrockIsFoundOnceFloodgateEnablesTest {

    private static final UUID BEDROCK_PLAYER = UUID.randomUUID();

    private static final BedrockDetector FLOODGATE = new BedrockDetector() {
        @Override
        public boolean isBedrock(UUID player) {
            return player.equals(BEDROCK_PLAYER);
        }

        @Override
        public String backend() {
            return "floodgate";
        }
    };

    @Test
    @DisplayName("A detector built before floodgate enables finds floodgate afterwards")
    void aDetectorBuiltEarlyFindsFloodgateLater() {
        AtomicInteger asked = new AtomicInteger();
        boolean[] floodgateUp = {false};
        LateBedrockDetector detector = new LateBedrockDetector(() -> {
            asked.incrementAndGet();
            return floodgateUp[0] ? FLOODGATE : BedrockDetector.NONE;
        });

        assertThat(detector.isBedrock(BEDROCK_PLAYER))
                .describedAs("before floodgate")
                .isFalse();
        floodgateUp[0] = true;

        assertThat(detector.isBedrock(BEDROCK_PLAYER))
                .describedAs("after floodgate")
                .isTrue();
        assertThat(detector.backend()).isEqualTo("floodgate");
    }

    @Test
    @DisplayName("Once found, floodgate is kept rather than asked for again")
    void onceFoundItIsKept() {
        AtomicInteger asked = new AtomicInteger();
        LateBedrockDetector detector = new LateBedrockDetector(() -> {
            asked.incrementAndGet();
            return FLOODGATE;
        });

        detector.isBedrock(BEDROCK_PLAYER);
        detector.isBedrock(BEDROCK_PLAYER);
        detector.backend();

        assertThat(asked).hasValue(1);
    }

    @Test
    @DisplayName("No source picks the library's detector or screen once, at build time")
    void nothingResolvesBedrockOnce() throws IOException {
        List<String> offences = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().startsWith("LateBedrock")) {
                    continue;
                }
                String source = Files.readString(file);
                if (source.matches("(?s).*(?<!Late)Bedrock(Detector|Screen)\\.forServer\\(.*")) {
                    offences.add(file.getFileName().toString());
                }
            }
        }

        assertThat(offences)
                .describedAs("picks Bedrock support once, before floodgate may have enabled")
                .isEmpty();
    }
}

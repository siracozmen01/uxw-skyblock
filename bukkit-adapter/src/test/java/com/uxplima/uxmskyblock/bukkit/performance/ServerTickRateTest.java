package com.uxplima.uxmskyblock.bukkit.performance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tick rate is read from any thread without throwing.
 *
 * <p>Folia refused the question off a region's thread, and the reset asked it there: the refusal threw
 * out of the reset before it began, the island stayed and every later attempt was told one was running.
 */
class ServerTickRateTest {

    @Test
    @DisplayName("Off every region the rate read last answers, and a full twenty before any was read")
    void offARegionTheLastRateAnswers() {
        AtomicBoolean onARegion = new AtomicBoolean(false);
        ServerTickRate rate = new ServerTickRate(() -> {
            if (!onARegion.get()) {
                throw new UnsupportedOperationException("Not on any region");
            }
            return new double[] {14.5, 18.0, 19.0};
        });

        assertThat(rate.getAsDouble()).isEqualTo(20.0);

        onARegion.set(true);
        assertThat(rate.getAsDouble()).isEqualTo(14.5);

        onARegion.set(false);
        assertThat(rate.getAsDouble()).isEqualTo(14.5);
    }
}

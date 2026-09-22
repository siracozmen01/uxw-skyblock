package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Something pushes the island authority lease forward.
 *
 * <p>A lease was taken once, when the island was made, and nothing renewed it: renewAuthority and
 * takeoverAuthority were written, tested against both engines, and had no caller anywhere in the
 * plugin. Every write that needs authority is refused once the lease runs out, so an island stopped
 * being able to use its own bank one lease after it was created.
 *
 * <p>The heartbeat has nowhere to be tested from, because the wiring it lives in needs a running
 * server to build. What can be checked is that it is still started and still stopped, which is the
 * shape that was missing.
 */
class TheAuthorityLeaseHasAHeartbeatTest {

    private static final Path WIRING =
            Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/bootstrap/IntegrationWiring.java");

    @Test
    @DisplayName("Enabling the plugin starts the heartbeat, and beats once before waiting")
    void thePluginStartsTheHeartbeat() throws IOException {
        String source = Files.readString(WIRING, StandardCharsets.UTF_8);

        assertThat(source)
                .describedAs("a server down longer than the lease must take its islands back before anybody banks")
                .contains("authorityService.heartbeat();");
        assertThat(source)
                .describedAs("and keep taking them, on the operator's interval")
                .contains("scheduler.repeatAsync(")
                .contains("authorityService::heartbeat");
    }

    @Test
    @DisplayName("Shutting the plugin down stops the heartbeat")
    void thePluginStopsTheHeartbeat() throws IOException {
        String source = Files.readString(WIRING, StandardCharsets.UTF_8);

        assertThat(source)
                .describedAs("a repeating task left behind outlives the plugin that made it")
                .contains("closeAuthorityHeartbeat();");
    }

    @Test
    @DisplayName("The lease and the beat are the operator's numbers, not numbers written in the code")
    void theOperatorSetsBoth() throws IOException {
        String source = Files.readString(WIRING, StandardCharsets.UTF_8);
        String config = Files.readString(Path.of("src/main/resources/config.conf"), StandardCharsets.UTF_8);

        assertThat(source).contains("config.nodeConfig().authorityLease()");
        assertThat(source).contains("config.nodeConfig().authorityHeartbeatInterval()");
        assertThat(config)
                .describedAs("both are written down where an operator can find them")
                .contains("authority-lease")
                .contains("authority-heartbeat-interval");
    }

    @Test
    @DisplayName("Starting the plugin finishes the island resets a crash left half done")
    void thePluginFinishesHalfDoneResets() throws IOException {
        String source = Files.readString(WIRING, StandardCharsets.UTF_8);

        assertThat(source)
                .describedAs("a crash between deleting the island and handing its slot back leaves the "
                        + "grid a hole that nothing ever fills")
                .contains("recoverIncompleteRecycles();")
                .contains("recycleService.recoverIncompleteOperations();");
    }

    @Test
    @DisplayName("The recovery reads where a slot is off the slot, not out of the code")
    void theSlotSaysWhereItIs() throws IOException {
        Path recycle = Path.of(
                "../core/src/main/java/com/uxplima/uxmskyblock/core/application/recycle/IslandRecycleService.java");
        String source = Files.readString(recycle, StandardCharsets.UTF_8);

        assertThat(source)
                .describedAs("this call passed a world name and 0, 0 written in the code")
                .doesNotContain("releaseSlot(op.targetSlot()");
        assertThat(source)
                .describedAs("the pool row is the only thing that knows where the slot is")
                .contains("spiralSlotPoolPort.findBySlotIndex(slotIndex)");
    }

    @Test
    @DisplayName("Nothing takes a lease for a day written in the code any more")
    void noLeaseIsWrittenInTheCode() throws IOException {
        Path create = Path.of(
                "../core/src/main/java/com/uxplima/uxmskyblock/core/application/island/CreateIslandUseCase.java");
        Path bankruptcy = Path.of(
                "../core/src/main/java/com/uxplima/uxmskyblock/core/application/bank/IslandBankruptcyService.java");

        assertThat(Files.readString(create, StandardCharsets.UTF_8))
                .describedAs("a day, written here, that nothing renewed")
                .doesNotContain("86400");
        assertThat(Files.readString(bankruptcy, StandardCharsets.UTF_8)).doesNotContain("86400");
    }
}

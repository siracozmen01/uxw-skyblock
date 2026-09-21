package com.uxplima.uxmskyblock.core.application.alliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A combat check must not be a database query.
 *
 * <p>Friendly fire shielding calls {@code areAllied} from the damage handler, once per hit, and it
 * read the alliance table every time. A fight between two players on allied islands was a query per
 * swing, on the event thread.
 */
class AllianceCheckIsNotPerHitTest {

    private static final IslandId ATTACKER = IslandId.of(UUID.randomUUID());
    private static final IslandId VICTIM = IslandId.of(UUID.randomUUID());

    private static IslandAllianceService service(IslandAllianceStoragePort port) {
        return new IslandAllianceService(port, 2, Duration.ofMinutes(5), true, true, true);
    }

    @Test
    @DisplayName("A hundred hits cost one query")
    void repeatedChecksCostOneQuery() {
        IslandAllianceStoragePort port = mock(IslandAllianceStoragePort.class);
        when(port.areAllied(ATTACKER, VICTIM)).thenReturn(true);
        IslandAllianceService service = service(port);

        for (int i = 0; i < 100; i++) {
            assertThat(service.areAllied(ATTACKER, VICTIM)).isTrue();
        }

        verify(port, times(1)).areAllied(ATTACKER, VICTIM);
    }

    @Test
    @DisplayName("Asking the other way round is the same question, not a second one")
    void theOrderOfThePairDoesNotMatter() {
        IslandAllianceStoragePort port = mock(IslandAllianceStoragePort.class);
        when(port.areAllied(ATTACKER, VICTIM)).thenReturn(true);
        when(port.areAllied(VICTIM, ATTACKER)).thenReturn(true);
        IslandAllianceService service = service(port);

        service.areAllied(ATTACKER, VICTIM);
        service.areAllied(VICTIM, ATTACKER);

        int asked = mockingDetails(port);
        assertThat(asked)
                .describedAs("the pair is one question in either order")
                .isEqualTo(1);
    }

    private static int mockingDetails(IslandAllianceStoragePort port) {
        return (int) org.mockito.Mockito.mockingDetails(port).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("areAllied"))
                .count();
    }

    @Test
    @DisplayName("Breaking an alliance is seen at once, not after the answer goes stale")
    void breakingAnAllianceIsSeenAtOnce() {
        IslandAllianceStoragePort port = mock(IslandAllianceStoragePort.class);
        when(port.areAllied(ATTACKER, VICTIM)).thenReturn(true);
        IslandAllianceService service = service(port);
        assertThat(service.areAllied(ATTACKER, VICTIM)).isTrue();

        when(port.areAllied(ATTACKER, VICTIM)).thenReturn(false);
        service.removeAlliance(ATTACKER, VICTIM);

        assertThat(service.areAllied(ATTACKER, VICTIM))
                .describedAs("this node broke the alliance, so it must not read its own stale answer")
                .isFalse();
    }
}

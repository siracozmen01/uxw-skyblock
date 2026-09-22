package com.uxplima.uxmskyblock.core.application.recycle;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.world.SpiralSlotPoolPort;
import com.uxplima.uxmskyblock.core.application.world.WorldGridAllocationPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.recycle.IslandRecycleOperation;
import com.uxplima.uxmskyblock.core.domain.recycle.IslandRecycleState;
import com.uxplima.uxmskyblock.core.domain.world.RecycledSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A reset a crash left half done is finished, and the grid slot goes back where it came from.
 *
 * <p>A reset deletes the island and then hands its slot back. A crash between the two leaves the
 * operation in CANONICAL_DELETE and the slot marked allocated for an island that no longer exists,
 * so the grid never reuses it and the world grows a hole per crash.
 *
 * <p>Where the slot is comes off the slot. It used to come from the world name
 * {@code "skyblock_world"} and the coordinates 0, 0 written into the recovery, on a server whose
 * world the operator names. The release keys on the slot index, so those only matter where the pool
 * has no row, and there they wrote a row claiming the slot sits at 0, 0 in a world that may not
 * exist: the next island recycled into it would be built on top of whatever is actually there.
 */
class TheHalfDoneResetIsFinishedTest {

    private IslandRecycleOperationPort recycleOperationPort;
    private SpiralSlotPoolPort spiralSlotPoolPort;
    private IslandRecycleService service;

    @BeforeEach
    void setUp() {
        recycleOperationPort = mock(IslandRecycleOperationPort.class);
        spiralSlotPoolPort = mock(SpiralSlotPoolPort.class);
        when(recycleOperationPort.findOperationsByState(any())).thenReturn(List.of());

        service = new IslandRecycleService(
                mock(IslandStoragePort.class),
                mock(WorldGridAllocationPort.class),
                spiralSlotPoolPort,
                mock(IslandVoidingPort.class),
                mock(IslandBackupPort.class),
                mock(OutboxPort.class),
                recycleOperationPort,
                Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC));
    }

    private static IslandRecycleOperation operation(long slot, IslandRecycleState state) {
        return new IslandRecycleOperation(
                UUID.randomUUID().toString(),
                IslandId.of(UUID.randomUUID()),
                PlayerUuid.of(UUID.randomUUID()),
                slot,
                state,
                null,
                null,
                Instant.parse("2026-06-01T11:00:00Z"),
                Instant.parse("2026-06-01T11:00:00Z"));
    }

    @Test
    @DisplayName("The slot goes back to the world and the coordinates the slot itself names")
    void theSlotSaysWhereItIs() {
        IslandRecycleOperation halfDone = operation(42L, IslandRecycleState.CANONICAL_DELETE);
        when(recycleOperationPort.findOperationsByState(IslandRecycleState.CANONICAL_DELETE))
                .thenReturn(List.of(halfDone));
        when(spiralSlotPoolPort.findBySlotIndex(42L))
                .thenReturn(Optional.of(new RecycledSlot(42L, "islands_alpha", 3000, -1500, true, null)));

        service.recoverIncompleteOperations();

        verify(spiralSlotPoolPort).releaseSlot(42L, "islands_alpha", 3000, -1500);
        verify(recycleOperationPort)
                .updateState(eq(halfDone.operationId()), eq(IslandRecycleState.SLOT_RELEASED), any(), any(), any());
        verify(recycleOperationPort)
                .updateState(eq(halfDone.operationId()), eq(IslandRecycleState.COMPLETED), any(), any(), any());
    }

    @Test
    @DisplayName("A slot the pool has never heard of is not invented")
    void anUnknownSlotIsNotInvented() {
        IslandRecycleOperation halfDone = operation(7L, IslandRecycleState.CANONICAL_DELETE);
        when(recycleOperationPort.findOperationsByState(IslandRecycleState.CANONICAL_DELETE))
                .thenReturn(List.of(halfDone));
        when(spiralSlotPoolPort.findBySlotIndex(7L)).thenReturn(Optional.empty());

        service.recoverIncompleteOperations();

        verify(spiralSlotPoolPort, never())
                .releaseSlot(
                        anyLong(),
                        anyString(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt());
        verify(recycleOperationPort)
                .updateState(eq(halfDone.operationId()), eq(IslandRecycleState.COMPLETED), any(), any(), any());
    }

    @Test
    @DisplayName("An operation whose slot was already released is only carried to completed")
    void analreadyReleasedSlotIsJustCompleted() {
        IslandRecycleOperation released = operation(9L, IslandRecycleState.SLOT_RELEASED);
        when(recycleOperationPort.findOperationsByState(IslandRecycleState.SLOT_RELEASED))
                .thenReturn(List.of(released));

        service.recoverIncompleteOperations();

        verify(spiralSlotPoolPort, never())
                .releaseSlot(
                        anyLong(),
                        anyString(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt());
        verify(recycleOperationPort)
                .updateState(eq(released.operationId()), eq(IslandRecycleState.COMPLETED), any(), any(), any());
    }

    @Test
    @DisplayName("A release that throws marks that one failed and the rest still run")
    void onefailureDoesNotStopTheRest() {
        IslandRecycleOperation first = operation(1L, IslandRecycleState.CANONICAL_DELETE);
        IslandRecycleOperation second = operation(2L, IslandRecycleState.CANONICAL_DELETE);
        when(recycleOperationPort.findOperationsByState(IslandRecycleState.CANONICAL_DELETE))
                .thenReturn(List.of(first, second));
        when(spiralSlotPoolPort.findBySlotIndex(1L)).thenThrow(new IllegalStateException("the database is gone"));
        when(spiralSlotPoolPort.findBySlotIndex(2L))
                .thenReturn(Optional.of(new RecycledSlot(2L, "islands_alpha", 0, 0, true, null)));

        service.recoverIncompleteOperations();

        verify(recycleOperationPort)
                .updateState(eq(first.operationId()), eq(IslandRecycleState.FAILED), any(), anyString(), any());
        verify(recycleOperationPort)
                .updateState(eq(second.operationId()), eq(IslandRecycleState.COMPLETED), any(), any(), any());
    }

    @Test
    @DisplayName("A server with nothing half done writes nothing")
    void nothingHalfDoneWritesNothing() {
        service.recoverIncompleteOperations();

        verify(recycleOperationPort, never()).updateState(anyString(), any(), any(), any(), any());
        verify(recycleOperationPort, times(2)).findOperationsByState(any());
    }
}

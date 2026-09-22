package com.uxplima.uxmskyblock.bukkit.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Reusable base test harness providing an in-memory MockBukkit Paper server environment.
 *
 * <p>Annotated with {@link Isolated} and {@link ExecutionMode#SAME_THREAD} to guarantee
 * that MockBukkit's singleton registry is never corrupted by parallel test threads.
 */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
public abstract class MockBukkitHarness {

    protected ServerMock server;

    @BeforeEach
    protected void setUpServer() {
        if (!MockBukkit.isMocked()) {
            server = MockBukkit.mock();
        } else {
            server = MockBukkit.getMock();
        }
    }

    @AfterEach
    protected void tearDownServer() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    protected PlayerMock createPlayer(String name) {
        return server.addPlayer(name);
    }

    /**
     * Waits for an assertion to come true, letting scheduled work run while it waits.
     *
     * <p>Waiting alone was not enough. Work handed to the server's own scheduler runs on a tick,
     * and nothing here ticked, so an assertion about anything that hops onto a player's thread
     * waited five seconds and then failed. The tick is what a running server does between one
     * command and the next.
     */
    protected static void eventually(Runnable assertion) {
        long start = System.currentTimeMillis();
        AssertionError last = null;
        while (System.currentTimeMillis() - start < 5000) {
            try {
                tickTheServer();
                assertion.run();
                return;
            } catch (AssertionError e) {
                last = e;
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
        }
        if (last != null) {
            throw last;
        }
    }

    /**
     * Runs one server tick, so scheduled work gets its turn.
     *
     * <p>A task that reaches something MockBukkit does not implement, a teleport being the usual
     * one, raises an exception JUnit reads as an assumption failure. That turns the whole test into
     * a silent skip, which guards nothing. It says only that this server is a mock, so it is
     * dropped here and the next tick carries on.
     */
    private static void tickTheServer() {
        if (!MockBukkit.isMocked()) {
            return;
        }
        try {
            MockBukkit.getMock().getScheduler().performOneTick();
        } catch (org.mockbukkit.mockbukkit.exception.UnimplementedOperationException mockGap) {
            // Nothing to do: the mock does not implement it, and the product is not at fault.
        }
    }
}

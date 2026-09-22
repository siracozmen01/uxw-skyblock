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
     * A world that can say which generator made it.
     *
     * <p>MockBukkit's own world answers that question as unimplemented, and the plugin asks it at
     * startup to tell the operator when the islands would land in generated terrain. A null generator
     * is the server's own terrain.
     */
    protected org.mockbukkit.mockbukkit.world.WorldMock addWorldMadeBy(
            String name, org.bukkit.generator.@org.jspecify.annotations.Nullable ChunkGenerator generator) {
        org.mockbukkit.mockbukkit.world.WorldMock world = new org.mockbukkit.mockbukkit.world.WorldMock() {
            @Override
            public org.bukkit.generator.@org.jspecify.annotations.Nullable ChunkGenerator getGenerator() {
                return generator;
            }
        };
        world.setName(name);
        server.addWorld(world);
        return world;
    }

    /** A player that refuses a synchronous teleport, the way Folia does. */
    protected PlayerMock createRegionThreadedPlayer(String name) {
        RegionThreadedPlayerMock player = new RegionThreadedPlayerMock(server, name);
        server.addPlayer(player);
        return player;
    }

    protected static void eventually(Runnable assertion) {
        long start = System.currentTimeMillis();
        AssertionError last = null;
        while (System.currentTimeMillis() - start < 5000) {
            try {
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
}

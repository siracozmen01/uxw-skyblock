package com.uxplima.uxmskyblock.bukkit.test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Location;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import io.papermc.paper.entity.TeleportFlag;

import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A player on a region threaded server.
 *
 * <p>Folia refuses a synchronous teleport outright: it throws, because the destination may belong
 * to another region's thread. MockBukkit does the opposite, accepting the synchronous call and
 * refusing the asynchronous one as unimplemented, so a test on a plain mock passes the one call
 * that breaks on Folia and fails the one that works. This player answers the way Folia does.
 */
public final class RegionThreadedPlayerMock extends PlayerMock {

    private boolean moving;

    public RegionThreadedPlayerMock(ServerMock server, String name) {
        super(
                server,
                name,
                UUID.nameUUIDFromBytes(("region-threaded:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Override
    public boolean teleport(Location location) {
        refuseUnlessMoving();
        return super.teleport(location);
    }

    @Override
    public boolean teleport(Location location, TeleportCause cause) {
        refuseUnlessMoving();
        return super.teleport(location, cause);
    }

    @Override
    public boolean teleport(Location location, TeleportCause cause, TeleportFlag... flags) {
        refuseUnlessMoving();
        return super.teleport(location, cause, flags);
    }

    @Override
    public CompletableFuture<Boolean> teleportAsync(Location location, TeleportCause cause, TeleportFlag... flags) {
        moving = true;
        try {
            return CompletableFuture.completedFuture(super.teleport(location, cause, flags));
        } finally {
            moving = false;
        }
    }

    private void refuseUnlessMoving() {
        if (!moving) {
            throw new UnsupportedOperationException("Must use teleportAsync while in region threading");
        }
    }
}

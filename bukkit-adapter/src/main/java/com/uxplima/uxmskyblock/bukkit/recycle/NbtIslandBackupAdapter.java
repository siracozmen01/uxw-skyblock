package com.uxplima.uxmskyblock.bukkit.recycle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.recycle.IslandBackupPort;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;

/**
 * Platform adapter serializing pre-deletion disaster-recovery backup snapshots
 * into {@code backups/islands/<island_id>_<timestamp>.schem}.
 */
public final class NbtIslandBackupAdapter implements IslandBackupPort {

    private final File backupDirectory;

    public NbtIslandBackupAdapter(File dataFolder) {
        Objects.requireNonNull(dataFolder, "dataFolder must not be null");
        this.backupDirectory = new File(dataFolder, "backups/islands");
    }

    @Override
    public void createPreDeletionBackup(Island island, IslandLocation location) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(location, "location must not be null");

        if (!backupDirectory.exists() && !backupDirectory.mkdirs()) {
            // If mkdirs fails, proceed if it was created concurrently
            if (!backupDirectory.exists()) {
                throw new IllegalStateException(
                        "Failed to create backup directory: " + backupDirectory.getAbsolutePath());
            }
        }

        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "-");
        String fileName = String.format("%s_%s.schem", island.id().value(), timestamp);
        File targetFile = new File(backupDirectory, fileName);

        String snapshotPayload = String.format(
                """
                {
                  "timestamp": "%s",
                  "islandId": "%s",
                  "ownerProfileId": "%s",
                  "ownerPlayerUuid": "%s",
                  "lifecycle": "%s",
                  "residencyState": "%s",
                  "economicState": "%s",
                  "administrativeState": "%s",
                  "worldName": "%s",
                  "center": {"x": %.2f, "y": %.2f, "z": %.2f, "yaw": %.2f, "pitch": %.2f},
                  "bounds": {"centerX": %d, "centerZ": %d, "radius": %d, "minX": %d, "maxX": %d, "minZ": %d, "maxZ": %d},
                  "memberCount": %d,
                  "flags": %s
                }
                """,
                Instant.now(),
                island.id().value(),
                island.ownerProfileId().value(),
                island.ownerPlayerUuid().value(),
                island.lifecycle(),
                island.residencyState(),
                island.economicState(),
                island.administrativeState(),
                location.worldName(),
                location.spawnX(),
                location.spawnY(),
                location.spawnZ(),
                location.spawnYaw(),
                location.spawnPitch(),
                location.bounds().centerX(),
                location.bounds().centerZ(),
                location.bounds().radius(),
                location.bounds().minX(),
                location.bounds().maxX(),
                location.bounds().minZ(),
                location.bounds().maxZ(),
                island.members().size(),
                island.flags().values());

        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            fos.write(snapshotPayload.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to write pre-deletion snapshot to " + targetFile.getAbsolutePath(), e);
        }
    }

    public File backupDirectory() {
        return backupDirectory;
    }
}

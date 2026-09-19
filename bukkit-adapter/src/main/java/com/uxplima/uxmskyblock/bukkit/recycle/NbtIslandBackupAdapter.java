package com.uxplima.uxmskyblock.bukkit.recycle;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;

import org.bukkit.Bukkit;
import org.bukkit.World;

import com.uxplima.uxmskyblock.core.application.recycle.IslandBackupPort;
import com.uxplima.uxmskyblock.core.application.snapshot.WorldDimensionSnapshotPort;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.jspecify.annotations.Nullable;

/**
 * Platform adapter serializing pre-deletion disaster-recovery backup snapshots
 * into compressed GZIP binary format at {@code backups/islands/<island_id>_<timestamp>.schem}.
 */
public final class NbtIslandBackupAdapter implements IslandBackupPort {

    private static final int BACKUP_MAGIC = 0x534B5942; // "SKYB"
    private static final int BACKUP_FORMAT_VERSION = 1;

    private final File backupDirectory;
    private final @Nullable WorldDimensionSnapshotPort worldDimensionSnapshotPort;

    public NbtIslandBackupAdapter(File dataFolder, @Nullable WorldDimensionSnapshotPort worldDimensionSnapshotPort) {
        Objects.requireNonNull(dataFolder, "dataFolder must not be null");
        this.backupDirectory = new File(dataFolder, "backups/islands");
        this.worldDimensionSnapshotPort = worldDimensionSnapshotPort;
    }

    public NbtIslandBackupAdapter(File dataFolder) {
        this(dataFolder, null);
    }

    @Override
    public String createPreDeletionBackup(Island island, IslandLocation location) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(location, "location must not be null");

        if (!backupDirectory.exists() && !backupDirectory.mkdirs()) {
            if (!backupDirectory.exists()) {
                throw new IllegalStateException(
                        "Failed to create backup directory: " + backupDirectory.getAbsolutePath());
            }
        }

        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "-");
        String fileName = String.format("%s_%s.schem", island.id().value(), timestamp);
        File targetFile = new File(backupDirectory, fileName);

        String metadataJson = String.format(
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
                island.members().size(),
                island.flags().values());

        byte[] metadataBytes = metadataJson.getBytes(StandardCharsets.UTF_8);

        try (FileOutputStream fos = new FileOutputStream(targetFile);
                GZIPOutputStream gzos = new GZIPOutputStream(fos);
                DataOutputStream dos = new DataOutputStream(gzos)) {

            // Magic header & version
            dos.writeInt(BACKUP_MAGIC);
            dos.writeInt(BACKUP_FORMAT_VERSION);

            // Domain identifiers and states
            dos.writeUTF(island.id().value().toString());
            dos.writeUTF(island.ownerProfileId().value().toString());
            dos.writeUTF(island.ownerPlayerUuid().value().toString());
            dos.writeUTF(island.lifecycle().name());
            dos.writeUTF(island.residencyState().name());
            dos.writeUTF(island.economicState().name());
            dos.writeUTF(island.administrativeState().name());

            // Location & spatial boundaries
            dos.writeUTF(location.worldName());
            dos.writeDouble(location.spawnX());
            dos.writeDouble(location.spawnY());
            dos.writeDouble(location.spawnZ());
            dos.writeFloat(location.spawnYaw());
            dos.writeFloat(location.spawnPitch());
            dos.writeInt(location.bounds().centerX());
            dos.writeInt(location.bounds().centerZ());
            dos.writeInt(location.bounds().radius());
            dos.writeInt(location.bounds().minX());
            dos.writeInt(location.bounds().maxX());
            dos.writeInt(location.bounds().minZ());
            dos.writeInt(location.bounds().maxZ());
            dos.writeLong(Instant.now().toEpochMilli());

            // Metadata payload
            dos.writeInt(metadataBytes.length);
            dos.write(metadataBytes);

            // Block geometry and structure snapshot if world is accessible
            World world = Bukkit.getWorld(location.worldName());
            if (world != null) {
                dos.writeBoolean(true);
                dos.writeInt(location.bounds().radius());

                if (worldDimensionSnapshotPort != null) {
                    PrimaryGameplayRootRef rootRef = new PrimaryGameplayRootRef(
                            GameModeInstanceId.of(island.id().value()),
                            island.id().value().toString(),
                            "ISLAND",
                            Instant.now());
                    byte[] snapshotBytes =
                            worldDimensionSnapshotPort.captureWorldDimension(rootRef, DimensionId.OVERWORLD);
                    dos.writeInt(snapshotBytes.length);
                    dos.write(snapshotBytes);
                } else {
                    dos.writeInt(0);
                }
            } else {
                dos.writeBoolean(false);
            }

            dos.flush();
            gzos.finish();
            return targetFile.getAbsolutePath();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to write compressed disaster recovery backup snapshot to " + targetFile.getAbsolutePath(),
                    e);
        }
    }

    public File backupDirectory() {
        return backupDirectory;
    }
}

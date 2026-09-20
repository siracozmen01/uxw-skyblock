package com.uxplima.uxmskyblock.bukkit.recycle;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import org.bukkit.Bukkit;
import org.bukkit.World;

import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.gamemode.GameModeHierarchyStoragePort;
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
 * into canonical BackupSet directories containing manifest.json, SHA-256 verified data files,
 * and AVAILABLE.marker (Sections 2.29, 9, and PERSISTENCE_SPECIFICATION.md).
 */
public final class NbtIslandBackupAdapter implements IslandBackupPort {

    private static final int BACKUP_MAGIC = 0x534B5942; // "SKYB"
    private static final int BACKUP_FORMAT_VERSION = 1;
    public static final String DATA_FILE_NAME = "data.schem";

    private final File backupDirectory;
    private final @Nullable WorldDimensionSnapshotPort worldDimensionSnapshotPort;
    private final @Nullable GameModeHierarchyStoragePort gameModeHierarchyStoragePort;

    public NbtIslandBackupAdapter(
            File dataFolder,
            @Nullable WorldDimensionSnapshotPort worldDimensionSnapshotPort,
            @Nullable GameModeHierarchyStoragePort gameModeHierarchyStoragePort) {
        Objects.requireNonNull(dataFolder, "dataFolder must not be null");
        this.backupDirectory = new File(dataFolder, "backups/islands");
        this.worldDimensionSnapshotPort = worldDimensionSnapshotPort;
        this.gameModeHierarchyStoragePort = gameModeHierarchyStoragePort;
    }

    public NbtIslandBackupAdapter(File dataFolder, @Nullable WorldDimensionSnapshotPort worldDimensionSnapshotPort) {
        this(dataFolder, worldDimensionSnapshotPort, null);
    }

    public NbtIslandBackupAdapter(File dataFolder) {
        this(dataFolder, null, null);
    }

    @Override
    public String createPreDeletionBackup(Island island, IslandLocation location) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(location, "location must not be null");

        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "-");
        String dirName = String.format("%s_%s", island.id().value(), timestamp);
        File backupSetDir = new File(backupDirectory, dirName);

        if (!backupSetDir.exists() && !backupSetDir.mkdirs()) {
            if (!backupSetDir.exists()) {
                throw new IllegalStateException("Failed to create backup directory: " + backupSetDir.getAbsolutePath());
            }
        }

        File dataFile = new File(backupSetDir, DATA_FILE_NAME);

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

        PrimaryGameplayRootRef rootRef = resolvePrimaryGameplayRootRef(island);

        // 1. Write data file
        try (FileOutputStream fos = new FileOutputStream(dataFile);
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
            World world = (Bukkit.getServer() != null) ? Bukkit.getWorld(location.worldName()) : null;
            if (world != null) {
                dos.writeBoolean(true);
                dos.writeInt(location.bounds().radius());

                if (worldDimensionSnapshotPort != null) {
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
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to write compressed disaster recovery backup snapshot to " + dataFile.getAbsolutePath(), e);
        }

        // 2. Compute SHA-256 and size of data file
        String sha256 = computeFileSha256(dataFile);
        long sizeBytes = dataFile.length();

        // 3. Write manifest.json
        File manifestFile = new File(backupSetDir, BackupService.MANIFEST_FILE_NAME);
        UUID backupSetId = UUID.randomUUID();
        String manifestJson = String.format(
                """
                {
                  "backupSetId": "%s",
                  "backupType": "ROOT_BACKUP",
                  "rootTypeId": "ISLAND",
                  "rootKey": "%s",
                  "createdAt": "%s",
                  "authorityEpoch": 1,
                  "dbVersion": 1,
                  "schemaVersion": 1,
                  "pluginVersion": "1.0.0",
                  "artifacts": {
                    "%s": {
                      "filename": "%s",
                      "sizeBytes": %d,
                      "sha256Checksum": "%s"
                    }
                  },
                  "consistencyResult": "FULL_RESTORE_CONSISTENT"
                }
                """,
                backupSetId, island.id().value(), Instant.now(), DATA_FILE_NAME, DATA_FILE_NAME, sizeBytes, sha256);

        try {
            Files.writeString(manifestFile.toPath(), manifestJson, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write manifest.json to " + manifestFile.getAbsolutePath(), e);
        }

        // 4. Write AVAILABLE.marker strictly LAST to seal the BackupSet
        File markerFile = new File(backupSetDir, BackupService.AVAILABILITY_MARKER_FILE_NAME);
        File tempMarkerFile = new File(backupSetDir, BackupService.AVAILABILITY_MARKER_FILE_NAME + ".tmp");
        try {
            Files.writeString(tempMarkerFile.toPath(), Instant.now().toString(), StandardCharsets.UTF_8);
            Files.move(
                    tempMarkerFile.toPath(),
                    markerFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write AVAILABLE.marker to " + markerFile.getAbsolutePath(), e);
        }

        return backupSetDir.getAbsolutePath();
    }

    private PrimaryGameplayRootRef resolvePrimaryGameplayRootRef(Island island) {
        if (gameModeHierarchyStoragePort != null) {
            var optRef = gameModeHierarchyStoragePort.findRootRefByRootId(
                    island.id().value().toString(), "ISLAND");
            if (optRef.isPresent()) {
                return optRef.get();
            }
            var optInstance = gameModeHierarchyStoragePort.findInstanceByProfileId(island.ownerProfileId());
            if (optInstance.isPresent()) {
                return PrimaryGameplayRootRef.forIsland(
                        optInstance.get().id(), island.id().value().toString(), Instant.now());
            }
        }
        GameModeInstanceId fallbackInstanceId =
                GameModeInstanceId.of(island.ownerProfileId().value());
        return PrimaryGameplayRootRef.forIsland(
                fallbackInstanceId, island.id().value().toString(), Instant.now());
    }

    private static String computeFileSha256(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("Failed to compute SHA-256 for " + file.getAbsolutePath(), e);
        }
    }

    public File backupDirectory() {
        return backupDirectory;
    }
}

package com.uxplima.uxmskyblock.core.application.backup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.core.domain.backup.BackupArtifact;
import com.uxplima.uxmskyblock.core.domain.backup.BackupCatalogRecord;
import com.uxplima.uxmskyblock.core.domain.backup.BackupLifecycleState;
import com.uxplima.uxmskyblock.core.domain.backup.BackupManifest;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.backup.BackupType;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.core.domain.storage.StorageObjectMetadata;

/**
 * Domain application service orchestrating backup publication, retention deletion,
 * disaster discovery without live SQL databases, and checksum-guarded restore validation.
 */
public final class BackupService {

    public static final String MANIFEST_FILE_NAME = "manifest.json";
    public static final String AVAILABILITY_MARKER_FILE_NAME = "AVAILABLE.marker";

    private final BackupCatalogPort catalogPort;
    private final List<ObjectStoragePort> storageDestinations;

    public BackupService(BackupCatalogPort catalogPort, List<ObjectStoragePort> storageDestinations) {
        this.catalogPort = Objects.requireNonNull(catalogPort, "catalogPort");
        if (storageDestinations == null || storageDestinations.isEmpty()) {
            throw new IllegalArgumentException("At least one storage destination must be configured");
        }
        this.storageDestinations = List.copyOf(storageDestinations);
    }

    public BackupService(BackupCatalogPort catalogPort, ObjectStoragePort singleDestination) {
        this(catalogPort, List.of(singleDestination));
    }

    public static String computeSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    /**
     * Publishes a complete backup generation across all configured storage destinations.
     *
     * <p>Enforces the canonical publication ordering invariant:
     * <ol>
     *   <li>Record operational metadata in catalog with {@code UPLOADING} state.</li>
     *   <li>Upload artifact files to each destination.</li>
     *   <li>Upload immutable {@code manifest.json} to each destination.</li>
     *   <li>Verify uploaded artifact checksums against the manifest.</li>
     *   <li>Upload {@code AVAILABLE.marker} strictly LAST to each destination.</li>
     *   <li>Transition catalog record to {@code AVAILABLE} only when all destinations succeed.</li>
     * </ol>
     *
     * @param bucket target storage bucket
     * @param rootPrefix base path prefix for this backup generation
     * @param record initial catalog record
     * @param manifest immutable backup manifest
     * @param artifactPayloads mapping of relative filenames to binary data
     * @return true if publication succeeded across all destinations
     */
    public boolean publishBackup(
            StorageBucket bucket,
            String rootPrefix,
            BackupCatalogRecord record,
            BackupManifest manifest,
            Map<String, byte[]> artifactPayloads) {

        Objects.requireNonNull(bucket, "bucket");
        Objects.requireNonNull(rootPrefix, "rootPrefix");
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(artifactPayloads, "artifactPayloads");

        catalogPort.save(record);
        catalogPort.updateState(record.backupSetId(), BackupLifecycleState.UPLOADING, null);

        String normalizedPrefix =
                rootPrefix.endsWith("/") ? rootPrefix.substring(0, rootPrefix.length() - 1) : rootPrefix;

        // Verify all mandatory artifacts are provided and checksums match
        for (Map.Entry<String, BackupArtifact> entry : manifest.artifacts().entrySet()) {
            String filename = entry.getKey();
            BackupArtifact expected = entry.getValue();
            byte[] payload = artifactPayloads.get(filename);
            if (payload == null) {
                catalogPort.updateState(
                        record.backupSetId(),
                        BackupLifecycleState.FAILED,
                        "Missing mandatory artifact payload: " + filename);
                return false;
            }
            String computedHash = computeSha256(payload);
            if (!computedHash.equalsIgnoreCase(expected.sha256Checksum())) {
                catalogPort.updateState(
                        record.backupSetId(),
                        BackupLifecycleState.FAILED,
                        "Checksum mismatch on artifact: " + filename);
                return false;
            }
        }

        boolean allDestinationsSucceeded = true;
        List<ObjectStoragePort> succeededDestinations = new ArrayList<>();

        for (ObjectStoragePort destination : storageDestinations) {
            try {
                // 1. Upload artifacts
                for (Map.Entry<String, byte[]> entry : artifactPayloads.entrySet()) {
                    String artifactKey = normalizedPrefix + "/" + entry.getKey();
                    byte[] data = entry.getValue();
                    StorageObjectMetadata meta = StorageObjectMetadata.of(
                            "application/octet-stream", data.length, computeSha256(data), Instant.now());
                    destination.putObject(bucket, artifactKey, data, meta);
                }

                // 2. Upload immutable manifest
                String manifestKey = normalizedPrefix + "/" + MANIFEST_FILE_NAME;
                byte[] manifestBytes = serializeManifest(manifest);
                StorageObjectMetadata manifestMeta = StorageObjectMetadata.of(
                        "application/json", manifestBytes.length, computeSha256(manifestBytes), Instant.now());
                destination.putObject(bucket, manifestKey, manifestBytes, manifestMeta);

                // 3. Publish AVAILABLE.marker strictly LAST
                String markerKey = normalizedPrefix + "/" + AVAILABILITY_MARKER_FILE_NAME;
                byte[] markerBytes = "AVAILABLE".getBytes(StandardCharsets.UTF_8);
                StorageObjectMetadata markerMeta = StorageObjectMetadata.of(
                        "text/plain", markerBytes.length, computeSha256(markerBytes), Instant.now());
                destination.putObject(bucket, markerKey, markerBytes, markerMeta);

                succeededDestinations.add(destination);
            } catch (Exception e) {
                allDestinationsSucceeded = false;
            }
        }

        if (allDestinationsSucceeded) {
            catalogPort.updateState(record.backupSetId(), BackupLifecycleState.AVAILABLE, null);
            return true;
        } else if (!succeededDestinations.isEmpty()) {
            catalogPort.updateState(
                    record.backupSetId(),
                    BackupLifecycleState.PARTIAL,
                    "Mirrored publication failed on one or more destinations");
            return false;
        } else {
            catalogPort.updateState(
                    record.backupSetId(), BackupLifecycleState.FAILED, "Publication failed across all destinations");
            return false;
        }
    }

    /**
     * Deletes a backup generation across all configured destinations.
     *
     * <p>Enforces the canonical deletion ordering invariant:
     * <ol>
     *   <li>Transition catalog record to {@code DELETING}.</li>
     *   <li>Delete {@code AVAILABLE.marker} strictly FIRST from each destination.</li>
     *   <li>Delete artifact files.</li>
     *   <li>Delete {@code manifest.json}.</li>
     *   <li>Transition catalog record to {@code DELETED} only if all destinations succeed.</li>
     * </ol>
     *
     * @param bucket target storage bucket
     * @param rootPrefix base path prefix for this backup generation
     * @param backupSetId backup ID
     * @param manifest previously published manifest describing artifacts to delete
     * @return true if deletion completed cleanly across all destinations
     */
    public boolean deleteBackup(
            StorageBucket bucket, String rootPrefix, BackupSetId backupSetId, BackupManifest manifest) {

        Objects.requireNonNull(bucket, "bucket");
        Objects.requireNonNull(rootPrefix, "rootPrefix");
        Objects.requireNonNull(backupSetId, "backupSetId");

        catalogPort.updateState(backupSetId, BackupLifecycleState.DELETING, null);
        String normalizedPrefix =
                rootPrefix.endsWith("/") ? rootPrefix.substring(0, rootPrefix.length() - 1) : rootPrefix;

        boolean allSucceeded = true;

        for (ObjectStoragePort destination : storageDestinations) {
            try {
                // 1. Remove AVAILABLE.marker strictly FIRST to invalidate discovery
                String markerKey = normalizedPrefix + "/" + AVAILABILITY_MARKER_FILE_NAME;
                destination.deleteObject(bucket, markerKey);

                // 2. Delete artifacts
                if (manifest != null) {
                    for (String filename : manifest.artifacts().keySet()) {
                        destination.deleteObject(bucket, normalizedPrefix + "/" + filename);
                    }
                }

                // 3. Delete manifest
                destination.deleteObject(bucket, normalizedPrefix + "/" + MANIFEST_FILE_NAME);
            } catch (Exception e) {
                allSucceeded = false;
            }
        }

        if (allSucceeded) {
            catalogPort.updateState(backupSetId, BackupLifecycleState.DELETED, null);
            return true;
        } else {
            // Keep in RECOVERY_REQUIRED or DELETING so it is NEVER eligible for restore
            catalogPort.updateState(
                    backupSetId,
                    BackupLifecycleState.RECOVERY_REQUIRED,
                    "Deletion incomplete on one or more destinations");
            return false;
        }
    }

    /**
     * Discovers and verifies eligible backup candidates from a storage destination
     * without requiring an active SQL database connection.
     *
     * <p>A backup is recognized as an {@code AVAILABLE} restore candidate if and only if:
     * <ol>
     *   <li>{@code AVAILABLE.marker} exists.</li>
     *   <li>{@code manifest.json} exists and parses cleanly.</li>
     *   <li>All declared artifacts exist in storage and their SHA-256 hashes match.</li>
     * </ol>
     *
     * @param destination storage destination to scan
     * @param bucket storage bucket
     * @param rootPrefix base prefix containing backup generation folders
     * @return list of verified and eligible backup manifests
     */
    public List<BackupManifest> discoverBackupsWithoutDatabase(
            ObjectStoragePort destination, StorageBucket bucket, String rootPrefix) {

        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(bucket, "bucket");
        Objects.requireNonNull(rootPrefix, "rootPrefix");

        String normalized = rootPrefix.endsWith("/") ? rootPrefix : rootPrefix + "/";
        List<String> objects = destination.listObjects(bucket, normalized);

        List<BackupManifest> candidates = new ArrayList<>();

        // Group discovered objects by folder
        for (String key : objects) {
            if (key.endsWith("/" + MANIFEST_FILE_NAME)) {
                String folderPrefix = key.substring(0, key.length() - MANIFEST_FILE_NAME.length() - 1);

                // Rule 1: AVAILABLE.marker must exist
                String markerKey = folderPrefix + "/" + AVAILABILITY_MARKER_FILE_NAME;
                if (!destination.exists(bucket, markerKey)) {
                    continue; // Skip: missing availability marker fails closed
                }

                // Rule 2: Load manifest
                Optional<byte[]> manifestBytes = destination.getObject(bucket, key);
                if (manifestBytes.isEmpty()) {
                    continue;
                }

                BackupManifest manifest;
                try {
                    manifest = deserializeManifest(manifestBytes.get());
                } catch (Exception e) {
                    continue; // Invalid manifest format fails closed
                }

                // Rule 3: Verify all artifacts exist and checksums match
                boolean valid = true;
                for (Map.Entry<String, BackupArtifact> entry :
                        manifest.artifacts().entrySet()) {
                    String artifactKey = folderPrefix + "/" + entry.getKey();
                    Optional<byte[]> artifactBytes = destination.getObject(bucket, artifactKey);
                    if (artifactBytes.isEmpty()) {
                        valid = false;
                        break;
                    }
                    String actualHash = computeSha256(artifactBytes.get());
                    if (!actualHash.equalsIgnoreCase(entry.getValue().sha256Checksum())) {
                        valid = false;
                        break;
                    }
                }

                if (valid) {
                    candidates.add(manifest);
                }
            }
        }

        return List.copyOf(candidates);
    }

    /**
     * Validates whether a proposed restore operation satisfies authority fencing and checksum invariants.
     *
     * @param manifest backup manifest to restore
     * @param currentAuthorityEpoch current authority lease epoch from active session
     * @param currentDbVersion current OCC database version
     * @return true if restore preconditions pass
     */
    public boolean validateRestorePreconditions(
            BackupManifest manifest, long currentAuthorityEpoch, long currentDbVersion) {

        Objects.requireNonNull(manifest, "manifest");

        // Fencing check: if epoch has advanced past expected, abort
        if (currentAuthorityEpoch > manifest.authorityEpoch()) {
            return false;
        }

        // OCC check: if database version has advanced past expected, abort
        if (currentDbVersion > manifest.dbVersion()) {
            return false;
        }

        return true;
    }

    private static byte[] serializeManifest(BackupManifest m) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"backupSetId\": \"").append(m.backupSetId()).append("\",\n");
        sb.append("  \"backupType\": \"").append(m.backupType()).append("\",\n");
        sb.append("  \"rootTypeId\": \"")
                .append(m.rootTypeId() != null ? m.rootTypeId() : "")
                .append("\",\n");
        sb.append("  \"rootKey\": \"")
                .append(m.rootKey() != null ? m.rootKey() : "")
                .append("\",\n");
        sb.append("  \"createdAt\": \"").append(m.createdAt()).append("\",\n");
        sb.append("  \"authorityEpoch\": ").append(m.authorityEpoch()).append(",\n");
        sb.append("  \"dbVersion\": ").append(m.dbVersion()).append(",\n");
        sb.append("  \"schemaVersion\": ").append(m.schemaVersion()).append(",\n");
        sb.append("  \"pluginVersion\": \"").append(m.pluginVersion()).append("\",\n");
        sb.append("  \"consistencyResult\": \"").append(m.consistencyResult()).append("\",\n");
        sb.append("  \"artifacts\": {\n");
        int count = 0;
        for (Map.Entry<String, BackupArtifact> entry : m.artifacts().entrySet()) {
            if (count > 0) sb.append(",\n");
            sb.append("    \"").append(entry.getKey()).append("\": {");
            sb.append("\"filename\": \"").append(entry.getValue().filename()).append("\", ");
            sb.append("\"sizeBytes\": ").append(entry.getValue().sizeBytes()).append(", ");
            sb.append("\"sha256Checksum\": \"")
                    .append(entry.getValue().sha256Checksum())
                    .append("\"}");
            count++;
        }
        sb.append("\n  }\n");
        sb.append("}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public List<ObjectStoragePort> storageDestinations() {
        return storageDestinations;
    }

    @SuppressWarnings("EmptyCatch")
    public Optional<BackupManifest> loadManifest(StorageBucket bucket, String rootPrefix) {
        Objects.requireNonNull(bucket, "bucket");
        Objects.requireNonNull(rootPrefix, "rootPrefix");
        String normalizedPrefix =
                rootPrefix.endsWith("/") ? rootPrefix.substring(0, rootPrefix.length() - 1) : rootPrefix;
        String manifestKey = normalizedPrefix + "/" + MANIFEST_FILE_NAME;
        String markerKey = normalizedPrefix + "/" + AVAILABILITY_MARKER_FILE_NAME;
        for (ObjectStoragePort destination : storageDestinations) {
            // A manifest is only a backup where its marker was published. Without the marker it is
            // a publication that stopped short, or a deletion that already began, and restoring it by
            // id put back whatever half of it was left, which discovery had rightly hidden.
            if (!destination.exists(bucket, markerKey)) {
                continue;
            }
            Optional<byte[]> opt = destination.getObject(bucket, manifestKey);
            if (opt.isPresent()) {
                try {
                    return Optional.of(deserializeManifest(opt.get()));
                } catch (Exception ignored) {
                    // Ignore corrupted or unparseable manifests and try next destination
                }
            }
        }
        return Optional.empty();
    }

    public static BackupManifest deserializeManifest(byte[] bytes) {
        String json = new String(bytes, StandardCharsets.UTF_8);
        String setId = extractStringField(json, "backupSetId");
        String typeStr = extractStringField(json, "backupType");
        String rootType = extractStringField(json, "rootTypeId");
        String rootKey = extractStringField(json, "rootKey");
        String createdAtStr = extractStringField(json, "createdAt");
        long epoch = extractLongField(json, "authorityEpoch");
        long dbVer = extractLongField(json, "dbVersion");
        int schemaVer = (int) extractLongField(json, "schemaVersion");
        String pluginVer = extractStringField(json, "pluginVersion");
        String consistency = extractStringField(json, "consistencyResult");

        // Parse artifacts
        java.util.Map<String, BackupArtifact> artifacts = new java.util.HashMap<>();
        int artStart = json.indexOf("\"artifacts\":");
        if (artStart != -1) {
            String sub = json.substring(artStart);
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "\"([^\"]+)\"\\s*:\\s*\\{\\s*\"filename\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"sizeBytes\"\\s*:\\s*(\\d+)\\s*,\\s*\"sha256Checksum\"\\s*:\\s*\"([^\"]+)\"");
            java.util.regex.Matcher m = p.matcher(sub);
            while (m.find()) {
                String key = m.group(1);
                String fname = m.group(2);
                long size = Long.parseLong(m.group(3));
                String hash = m.group(4);
                artifacts.put(key, new BackupArtifact(fname, size, hash));
            }
        }

        return new BackupManifest(
                BackupSetId.fromString(setId),
                BackupType.valueOf(typeStr),
                rootType.isEmpty() ? null : rootType,
                rootKey.isEmpty() ? null : rootKey,
                Instant.parse(createdAtStr),
                epoch,
                dbVer,
                schemaVer,
                pluginVer,
                artifacts,
                consistency);
    }

    private static String extractStringField(String json, String field) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"");
        java.util.regex.Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static long extractLongField(String json, String field) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)");
        java.util.regex.Matcher m = p.matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }
}

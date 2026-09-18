package com.uxplima.uxmskyblock.persistence.storage.s3;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.core.domain.storage.ObjectStorageCapability;
import com.uxplima.uxmskyblock.core.domain.storage.ObjectStorageProviderId;
import com.uxplima.uxmskyblock.core.domain.storage.ProviderVerificationPolicy;
import com.uxplima.uxmskyblock.core.domain.storage.S3AddressingMode;
import com.uxplima.uxmskyblock.core.domain.storage.S3StorageConfiguration;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.core.domain.storage.StorageChecksumMismatchException;
import com.uxplima.uxmskyblock.core.domain.storage.StorageObjectMetadata;

/**
 * Enterprise S3-compatible remote object storage adapter.
 *
 * <p>Supports AWS S3, Cloudflare R2, and generic S3-compatible endpoints with bounded
 * streaming, multipart transfers, SHA-256 application checksum validation, and strict credential redaction.
 */
public final class S3ObjectStorageAdapter implements ObjectStoragePort {

    private static final Pattern UPLOAD_ID_PATTERN =
            Pattern.compile("<UploadId>(.*?)</UploadId>", Pattern.CASE_INSENSITIVE);
    private static final Pattern KEY_PATTERN = Pattern.compile("<Key>(.*?)</Key>", Pattern.CASE_INSENSITIVE);

    private final S3StorageConfiguration configuration;
    private final S3HttpTransport transport;

    public S3ObjectStorageAdapter(S3StorageConfiguration configuration) {
        this(configuration, new JavaHttpClientTransport());
    }

    public S3ObjectStorageAdapter(S3StorageConfiguration configuration, S3HttpTransport transport) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");

        // Enforce verification policy fail-closed
        ProviderVerificationPolicy.validateVerificationStatus(configuration);
    }

    public S3StorageConfiguration configuration() {
        return configuration;
    }

    public S3HttpTransport transport() {
        return transport;
    }

    @Override
    public ObjectStorageProviderId providerId() {
        return switch (configuration.providerTarget()) {
            case AWS_S3 -> ObjectStorageProviderId.AWS_S3;
            case CLOUDFLARE_R2 -> ObjectStorageProviderId.CLOUDFLARE_R2;
            case GENERIC_S3 -> ObjectStorageProviderId.GENERIC_S3;
        };
    }

    @Override
    public Set<ObjectStorageCapability> capabilities() {
        return switch (configuration.providerTarget()) {
            case AWS_S3 ->
                EnumSet.of(
                        ObjectStorageCapability.MULTIPART_UPLOAD,
                        ObjectStorageCapability.RANGE_READ,
                        ObjectStorageCapability.SERVER_SIDE_COPY,
                        ObjectStorageCapability.CONDITIONAL_WRITE,
                        ObjectStorageCapability.PRESIGNED_URL,
                        ObjectStorageCapability.NATIVE_OBJECT_VERSIONING,
                        ObjectStorageCapability.OBJECT_LOCK,
                        ObjectStorageCapability.PROVIDER_LIFECYCLE_RULES,
                        ObjectStorageCapability.CHECKSUM_ALGORITHMS,
                        ObjectStorageCapability.STREAMING_TRANSFER);
            case CLOUDFLARE_R2 ->
                EnumSet.of(
                        ObjectStorageCapability.MULTIPART_UPLOAD,
                        ObjectStorageCapability.RANGE_READ,
                        ObjectStorageCapability.SERVER_SIDE_COPY,
                        ObjectStorageCapability.CONDITIONAL_WRITE,
                        ObjectStorageCapability.PRESIGNED_URL,
                        ObjectStorageCapability.CHECKSUM_ALGORITHMS,
                        ObjectStorageCapability.STREAMING_TRANSFER);
            case GENERIC_S3 ->
                EnumSet.of(
                        ObjectStorageCapability.MULTIPART_UPLOAD,
                        ObjectStorageCapability.RANGE_READ,
                        ObjectStorageCapability.CHECKSUM_ALGORITHMS,
                        ObjectStorageCapability.STREAMING_TRANSFER);
        };
    }

    @Override
    public void putObject(StorageBucket bucket, String objectKey, byte[] data, StorageObjectMetadata metadata) {
        Objects.requireNonNull(bucket, "bucket must not be null");
        Objects.requireNonNull(objectKey, "objectKey must not be null");
        Objects.requireNonNull(data, "data must not be null");

        String actualSha256 = computeSha256(data);
        if (metadata.sha256Checksum() != null && !metadata.sha256Checksum().isBlank()) {
            if (!actualSha256.equalsIgnoreCase(metadata.sha256Checksum())) {
                throw new StorageChecksumMismatchException("Payload checksum " + actualSha256
                        + " does not match expected metadata checksum " + metadata.sha256Checksum());
            }
        }

        URI uri = resolveObjectUri(bucket, objectKey, null);
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Type", List.of(metadata.contentType()));
        headers.put("Content-Length", List.of(String.valueOf(data.length)));
        headers.put("x-amz-meta-sha256", List.of(actualSha256));

        S3HttpRequest unsigned = S3HttpRequest.of("PUT", uri, headers, data);
        S3HttpRequest signed =
                AwsSigV4Signer.sign(unsigned, configuration.credentials(), configuration.region(), Instant.now());

        try (S3HttpResponse response = executeWithRetry(signed)) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("S3 putObject failed for key " + objectKey
                        + " with HTTP status " + response.statusCode()
                        + ": " + response.bodyString());
            }
        } catch (IOException e) {
            throw new RuntimeException("Network error persisting object " + objectKey, e);
        }
    }

    @Override
    public Optional<byte[]> getObject(StorageBucket bucket, String objectKey) {
        return openStream(bucket, objectKey).map(stream -> {
            try (stream) {
                return stream.readAllBytes();
            } catch (IOException e) {
                throw new RuntimeException("Failed to read payload for object " + objectKey, e);
            }
        });
    }

    @Override
    public Optional<InputStream> openStream(StorageBucket bucket, String objectKey) {
        Objects.requireNonNull(bucket, "bucket must not be null");
        Objects.requireNonNull(objectKey, "objectKey must not be null");

        URI uri = resolveObjectUri(bucket, objectKey, null);
        S3HttpRequest unsigned = S3HttpRequest.of("GET", uri, Map.of());
        S3HttpRequest signed =
                AwsSigV4Signer.sign(unsigned, configuration.credentials(), configuration.region(), Instant.now());

        try {
            S3HttpResponse response = executeWithRetry(signed);
            if (response.statusCode() == 404) {
                response.close();
                return Optional.empty();
            }
            if (!response.isSuccessful()) {
                String error = response.bodyString();
                response.close();
                throw new RuntimeException("S3 getObject failed for key " + objectKey + " with HTTP status "
                        + response.statusCode() + ": " + error);
            }
            return Optional.ofNullable(response.bodyStream());
        } catch (IOException e) {
            throw new RuntimeException("Failed to open stream for S3 object " + objectKey, e);
        }
    }

    @Override
    public void putStream(
            StorageBucket bucket, String objectKey, InputStream inputStream, StorageObjectMetadata metadata) {
        Objects.requireNonNull(bucket, "bucket must not be null");
        Objects.requireNonNull(objectKey, "objectKey must not be null");
        Objects.requireNonNull(inputStream, "inputStream must not be null");

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }

        DigestInputStream digestStream = new DigestInputStream(inputStream, digest);
        long partSize = configuration.partSizeBytes();
        long threshold = configuration.multipartThresholdBytes();

        try {
            // Read initial chunk to determine if single-part or multipart is required
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while (buffer.size() < threshold && (read = digestStream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }

            // Check if stream ended within threshold
            int nextByte = digestStream.read();
            if (nextByte == -1) {
                // Entire stream fits within threshold -> Single PUT
                byte[] data = buffer.toByteArray();
                String actualSha256 = HexFormat.of().formatHex(digest.digest());

                if (metadata.sha256Checksum() != null
                        && !metadata.sha256Checksum().isBlank()) {
                    if (!actualSha256.equalsIgnoreCase(metadata.sha256Checksum())) {
                        throw new StorageChecksumMismatchException("Payload checksum " + actualSha256
                                + " does not match expected metadata checksum " + metadata.sha256Checksum());
                    }
                }

                StorageObjectMetadata enriched =
                        StorageObjectMetadata.of(metadata.contentType(), data.length, actualSha256, Instant.now());
                putObject(bucket, objectKey, data, enriched);
                return;
            }

            // Otherwise, stream exceeds threshold -> MULTIPART UPLOAD
            validateRequiredCapabilities(Set.of(ObjectStorageCapability.MULTIPART_UPLOAD));

            String uploadId = initiateMultipartUpload(bucket, objectKey, metadata);
            List<PartETag> parts = new ArrayList<>();
            int partNumber = 1;

            try {
                // Upload buffered bytes as Part 1
                ByteArrayOutputStream partBuffer = new ByteArrayOutputStream();
                partBuffer.write(buffer.toByteArray());
                partBuffer.write(nextByte);

                while (partBuffer.size() < partSize && (read = digestStream.read(chunk)) != -1) {
                    partBuffer.write(chunk, 0, read);
                }

                byte[] partBytes = partBuffer.toByteArray();
                String etag = uploadPart(bucket, objectKey, uploadId, partNumber, partBytes);
                parts.add(new PartETag(partNumber++, etag));

                // Upload subsequent parts
                while (true) {
                    partBuffer.reset();
                    while (partBuffer.size() < partSize && (read = digestStream.read(chunk)) != -1) {
                        partBuffer.write(chunk, 0, read);
                    }
                    if (partBuffer.size() == 0) {
                        break;
                    }
                    partBytes = partBuffer.toByteArray();
                    etag = uploadPart(bucket, objectKey, uploadId, partNumber, partBytes);
                    parts.add(new PartETag(partNumber++, etag));
                }

                // Verify computed SHA-256 before completing
                String actualSha256 = HexFormat.of().formatHex(digest.digest());
                if (metadata.sha256Checksum() != null
                        && !metadata.sha256Checksum().isBlank()) {
                    if (!actualSha256.equalsIgnoreCase(metadata.sha256Checksum())) {
                        abortMultipartUpload(bucket, objectKey, uploadId);
                        throw new StorageChecksumMismatchException("Multipart stream checksum " + actualSha256
                                + " does not match expected metadata checksum " + metadata.sha256Checksum());
                    }
                }

                completeMultipartUpload(bucket, objectKey, uploadId, parts);

            } catch (Exception uploadError) {
                // Interrupted / failed multipart upload: MUST abort to avoid orphaned parts!
                try {
                    abortMultipartUpload(bucket, objectKey, uploadId);
                } catch (Exception abortError) {
                    uploadError.addSuppressed(abortError);
                }
                throw uploadError;
            }

        } catch (IOException e) {
            throw new RuntimeException("Streaming upload failed for key " + objectKey, e);
        }
    }

    public String initiateMultipartUpload(StorageBucket bucket, String objectKey, StorageObjectMetadata metadata)
            throws IOException {
        URI uri = resolveObjectUri(bucket, objectKey, "uploads=");
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Type", List.of(metadata.contentType()));
        if (metadata.sha256Checksum() != null) {
            headers.put("x-amz-meta-sha256", List.of(metadata.sha256Checksum()));
        }

        S3HttpRequest unsigned = S3HttpRequest.of("POST", uri, headers);
        S3HttpRequest signed =
                AwsSigV4Signer.sign(unsigned, configuration.credentials(), configuration.region(), Instant.now());

        try (S3HttpResponse response = executeWithRetry(signed)) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to initiate multipart upload for " + objectKey + " HTTP status "
                        + response.statusCode() + ": " + response.bodyString());
            }

            String body = response.bodyString();
            Matcher matcher = UPLOAD_ID_PATTERN.matcher(body);
            if (matcher.find()) {
                return matcher.group(1);
            }
            throw new IOException("UploadId tag not found in initiate multipart response: " + body);
        }
    }

    public String uploadPart(StorageBucket bucket, String objectKey, String uploadId, int partNumber, byte[] partData)
            throws IOException {
        String query = "partNumber=" + partNumber + "&uploadId=" + URLEncoder.encode(uploadId, StandardCharsets.UTF_8);
        URI uri = resolveObjectUri(bucket, objectKey, query);

        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Length", List.of(String.valueOf(partData.length)));

        S3HttpRequest unsigned = S3HttpRequest.of("PUT", uri, headers, partData);
        S3HttpRequest signed =
                AwsSigV4Signer.sign(unsigned, configuration.credentials(), configuration.region(), Instant.now());

        try (S3HttpResponse response = executeWithRetry(signed)) {
            if (!response.isSuccessful()) {
                throw new IOException("Upload part " + partNumber + " failed for " + objectKey + " HTTP status "
                        + response.statusCode() + ": " + response.bodyString());
            }

            return response.firstHeader("ETag")
                    .orElseGet(() -> response.firstHeader("etag").orElse("\"part-" + partNumber + "\""));
        }
    }

    public void completeMultipartUpload(StorageBucket bucket, String objectKey, String uploadId, List<PartETag> parts)
            throws IOException {
        String query = "uploadId=" + URLEncoder.encode(uploadId, StandardCharsets.UTF_8);
        URI uri = resolveObjectUri(bucket, objectKey, query);

        StringBuilder xml = new StringBuilder("<CompleteMultipartUpload>");
        for (PartETag part : parts) {
            xml.append("<Part>")
                    .append("<PartNumber>")
                    .append(part.partNumber())
                    .append("</PartNumber>")
                    .append("<ETag>")
                    .append(part.etag())
                    .append("</ETag>")
                    .append("</Part>");
        }
        xml.append("</CompleteMultipartUpload>");

        byte[] body = xml.toString().getBytes(StandardCharsets.UTF_8);
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Type", List.of("application/xml"));
        headers.put("Content-Length", List.of(String.valueOf(body.length)));

        S3HttpRequest unsigned = S3HttpRequest.of("POST", uri, headers, body);
        S3HttpRequest signed =
                AwsSigV4Signer.sign(unsigned, configuration.credentials(), configuration.region(), Instant.now());

        try (S3HttpResponse response = executeWithRetry(signed)) {
            if (!response.isSuccessful()) {
                throw new IOException("Complete multipart upload failed for " + objectKey + " HTTP status "
                        + response.statusCode() + ": " + response.bodyString());
            }
        }
    }

    public void abortMultipartUpload(StorageBucket bucket, String objectKey, String uploadId) {
        try {
            String query = "uploadId=" + URLEncoder.encode(uploadId, StandardCharsets.UTF_8);
            URI uri = resolveObjectUri(bucket, objectKey, query);

            S3HttpRequest unsigned = S3HttpRequest.of("DELETE", uri, Map.of());
            S3HttpRequest signed =
                    AwsSigV4Signer.sign(unsigned, configuration.credentials(), configuration.region(), Instant.now());

            try (S3HttpResponse response = executeWithRetry(signed)) {
                if (response.isSuccessful()) {
                    // Abort succeeded
                }
            }
        } catch (Exception ignored) {
            // Best effort abort
        }
    }

    @Override
    public boolean exists(StorageBucket bucket, String objectKey) {
        Objects.requireNonNull(bucket, "bucket must not be null");
        Objects.requireNonNull(objectKey, "objectKey must not be null");

        URI uri = resolveObjectUri(bucket, objectKey, null);
        S3HttpRequest unsigned = S3HttpRequest.of("HEAD", uri, Map.of());
        S3HttpRequest signed =
                AwsSigV4Signer.sign(unsigned, configuration.credentials(), configuration.region(), Instant.now());

        try (S3HttpResponse response = executeWithRetry(signed)) {
            return response.statusCode() == 200;
        } catch (IOException e) {
            throw new RuntimeException("S3 exists check failed for " + objectKey, e);
        }
    }

    @Override
    public void deleteObject(StorageBucket bucket, String objectKey) {
        Objects.requireNonNull(bucket, "bucket must not be null");
        Objects.requireNonNull(objectKey, "objectKey must not be null");

        URI uri = resolveObjectUri(bucket, objectKey, null);
        S3HttpRequest unsigned = S3HttpRequest.of("DELETE", uri, Map.of());
        S3HttpRequest signed =
                AwsSigV4Signer.sign(unsigned, configuration.credentials(), configuration.region(), Instant.now());

        try (S3HttpResponse response = executeWithRetry(signed)) {
            if (!response.isSuccessful() && response.statusCode() != 404) {
                throw new RuntimeException(
                        "S3 deleteObject failed for " + objectKey + " HTTP status " + response.statusCode());
            }
        } catch (IOException e) {
            throw new RuntimeException("S3 deleteObject error for " + objectKey, e);
        }
    }

    @Override
    public List<String> listObjects(StorageBucket bucket, String prefix) {
        Objects.requireNonNull(bucket, "bucket must not be null");
        String sanitizedPrefix = sanitizeKey(prefix != null ? prefix : "");
        String query = "list-type=2"
                + (sanitizedPrefix.isEmpty()
                        ? ""
                        : "&prefix=" + URLEncoder.encode(sanitizedPrefix, StandardCharsets.UTF_8));

        URI uri = resolveBucketRootUri(bucket, query);
        S3HttpRequest unsigned = S3HttpRequest.of("GET", uri, Map.of());
        S3HttpRequest signed =
                AwsSigV4Signer.sign(unsigned, configuration.credentials(), configuration.region(), Instant.now());

        try (S3HttpResponse response = executeWithRetry(signed)) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("S3 listObjects failed HTTP status " + response.statusCode());
            }

            String body = response.bodyString();
            List<String> keys = new ArrayList<>();
            Matcher matcher = KEY_PATTERN.matcher(body);
            while (matcher.find()) {
                keys.add(matcher.group(1));
            }
            return Collections.unmodifiableList(keys);
        } catch (IOException e) {
            throw new RuntimeException("S3 listObjects error for prefix " + prefix, e);
        }
    }

    @Override
    public Optional<StorageObjectMetadata> getMetadata(StorageBucket bucket, String objectKey) {
        Objects.requireNonNull(bucket, "bucket must not be null");
        Objects.requireNonNull(objectKey, "objectKey must not be null");

        URI uri = resolveObjectUri(bucket, objectKey, null);
        S3HttpRequest unsigned = S3HttpRequest.of("HEAD", uri, Map.of());
        S3HttpRequest signed =
                AwsSigV4Signer.sign(unsigned, configuration.credentials(), configuration.region(), Instant.now());

        try (S3HttpResponse response = executeWithRetry(signed)) {
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (!response.isSuccessful()) {
                throw new RuntimeException("S3 getMetadata failed HTTP status " + response.statusCode());
            }

            long contentLength =
                    response.firstHeader("Content-Length").map(Long::parseLong).orElse(0L);
            String contentType = response.firstHeader("Content-Type").orElse("application/octet-stream");
            String sha256 = response.firstHeader("x-amz-meta-sha256")
                    .orElseGet(() -> response.firstHeader("ETag")
                            .map(tag -> tag.replace("\"", ""))
                            .orElse(""));
            Instant lastModified = response.firstHeader("Last-Modified")
                    .map(h -> {
                        try {
                            return DateTimeFormatter.RFC_1123_DATE_TIME.parse(h, Instant::from);
                        } catch (Exception ignored) {
                            return Instant.now();
                        }
                    })
                    .orElseGet(Instant::now);

            return Optional.of(StorageObjectMetadata.of(contentType, contentLength, sha256, lastModified));
        } catch (IOException e) {
            throw new RuntimeException("S3 getMetadata network error for " + objectKey, e);
        }
    }

    private S3HttpResponse executeWithRetry(S3HttpRequest request) throws IOException {
        int maxRetries = configuration.maxRetries();
        int attempts = 0;
        long backoffMs = 100;

        while (true) {
            attempts++;
            try {
                S3HttpResponse response = transport.send(request);
                if (response.statusCode() >= 500 && attempts <= maxRetries) {
                    response.close();
                    sleepBackoff(backoffMs);
                    backoffMs *= 2;
                    continue;
                }
                return response;
            } catch (IOException | InterruptedException e) {
                if (attempts > maxRetries) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        throw new IOException("S3 request interrupted after retries", e);
                    }
                    throw (IOException) e;
                }
                sleepBackoff(backoffMs);
                backoffMs *= 2;
            }
        }
    }

    private void sleepBackoff(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private URI resolveBucketRootUri(StorageBucket bucket, String query) {
        String bucketName = bucket.name();
        URI base = configuration.endpoint();
        String scheme = base.getScheme() != null ? base.getScheme() : "https";
        String host = base.getHost();
        int port = base.getPort();

        try {
            if (configuration.addressingMode() == S3AddressingMode.PATH_STYLE) {
                String path = "/" + bucketName;
                return new URI(scheme, null, host, port, path, query, null);
            } else {
                String virtualHost = bucketName + "." + host;
                return new URI(scheme, null, virtualHost, port, "/", query, null);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URI construction for bucket: " + bucketName, e);
        }
    }

    private URI resolveObjectUri(StorageBucket bucket, String objectKey, String query) {
        String sanitizedKey = sanitizeKey(objectKey);
        if (configuration.pathPrefix() != null && !configuration.pathPrefix().isBlank()) {
            sanitizedKey = configuration.pathPrefix().trim() + "/" + sanitizedKey;
        }

        String bucketName = bucket.name();
        URI base = configuration.endpoint();
        String scheme = base.getScheme() != null ? base.getScheme() : "https";
        String host = base.getHost();
        int port = base.getPort();

        try {
            if (configuration.addressingMode() == S3AddressingMode.PATH_STYLE) {
                String path = "/" + bucketName + "/" + sanitizedKey;
                return new URI(scheme, null, host, port, path, query, null);
            } else {
                String virtualHost = bucketName + "." + host;
                String path = "/" + sanitizedKey;
                return new URI(scheme, null, virtualHost, port, path, query, null);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URI construction for key: " + objectKey, e);
        }
    }

    private String sanitizeKey(String objectKey) {
        String sanitized = objectKey.replace('\\', '/');
        while (sanitized.startsWith("/")) {
            sanitized = sanitized.substring(1);
        }
        if (sanitized.contains("..")) {
            throw new IllegalArgumentException("Path traversal attempt in S3 key: " + objectKey);
        }
        return sanitized;
    }

    private static String computeSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record PartETag(int partNumber, String etag) {}
}

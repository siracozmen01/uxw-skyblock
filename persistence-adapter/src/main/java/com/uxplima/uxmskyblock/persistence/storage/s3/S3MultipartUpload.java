package com.uxplima.uxmskyblock.persistence.storage.s3;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.core.domain.storage.StorageObjectMetadata;

/**
 * The four calls that make up an S3 multipart upload.
 *
 * <p>A multipart upload is a transaction the provider holds open, and an upload abandoned halfway
 * leaves parts behind that the bucket owner keeps paying for. Every path that can fail must reach
 * {@link #abortMultipartUpload}, which is why the four belong together rather than scattered
 * among the single object calls.
 */
final class S3MultipartUpload {

    private static final Logger LOGGER = Logger.getLogger(S3MultipartUpload.class.getName());

    private static final Pattern UPLOAD_ID_PATTERN =
            Pattern.compile("<UploadId>(.*?)</UploadId>", Pattern.CASE_INSENSITIVE);

    /** One uploaded part, and the tag the provider wants back when the upload is completed. */
    public record PartETag(int partNumber, String etag) {}

    private final S3Requests requests;

    S3MultipartUpload(S3Requests requests) {
        this.requests = Objects.requireNonNull(requests, "requests must not be null");
    }

    String initiateMultipartUpload(StorageBucket bucket, String objectKey, StorageObjectMetadata metadata)
            throws IOException {
        URI uri = requests.objectUri(bucket, objectKey, "uploads=");
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Type", List.of(metadata.contentType()));
        if (metadata.sha256Checksum() != null) {
            headers.put("x-amz-meta-sha256", List.of(metadata.sha256Checksum()));
        }

        S3HttpRequest unsigned = S3HttpRequest.of("POST", uri, headers);
        try (S3HttpResponse response = requests.send(unsigned)) {
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

    String uploadPart(StorageBucket bucket, String objectKey, String uploadId, int partNumber, byte[] partData)
            throws IOException {
        String query = "partNumber=" + partNumber + "&uploadId=" + URLEncoder.encode(uploadId, StandardCharsets.UTF_8);
        URI uri = requests.objectUri(bucket, objectKey, query);

        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Length", List.of(String.valueOf(partData.length)));

        S3HttpRequest unsigned = S3HttpRequest.of("PUT", uri, headers, partData);
        try (S3HttpResponse response = requests.send(unsigned)) {
            if (!response.isSuccessful()) {
                throw new IOException("Upload part " + partNumber + " failed for " + objectKey + " HTTP status "
                        + response.statusCode() + ": " + response.bodyString());
            }

            return response.firstHeader("ETag")
                    .orElseGet(() -> response.firstHeader("etag").orElse("\"part-" + partNumber + "\""));
        }
    }

    void completeMultipartUpload(StorageBucket bucket, String objectKey, String uploadId, List<PartETag> parts)
            throws IOException {
        String query = "uploadId=" + URLEncoder.encode(uploadId, StandardCharsets.UTF_8);
        URI uri = requests.objectUri(bucket, objectKey, query);

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
        try (S3HttpResponse response = requests.send(unsigned)) {
            if (!response.isSuccessful()) {
                throw new IOException("Complete multipart upload failed for " + objectKey + " HTTP status "
                        + response.statusCode() + ": " + response.bodyString());
            }
        }
    }

    void abortMultipartUpload(StorageBucket bucket, String objectKey, String uploadId) {
        try {
            String query = "uploadId=" + URLEncoder.encode(uploadId, StandardCharsets.UTF_8);
            URI uri = requests.objectUri(bucket, objectKey, query);

            S3HttpRequest unsigned = S3HttpRequest.of("DELETE", uri, Map.of());
            try (S3HttpResponse response = requests.send(unsigned)) {
                if (!response.isSuccessful()) {
                    LOGGER.warning("The provider refused to abort the multipart upload " + uploadId + " for "
                            + objectKey + " with HTTP status " + response.statusCode()
                            + ". Parts may be left behind, and the bucket owner is billed for them.");
                }
            }
        } catch (Exception e) {
            // An abort is best effort: the upload already failed and this is the cleanup. It is not
            // silent, because the parts it failed to remove cost the bucket owner money every month.
            LOGGER.warning("Could not abort the multipart upload " + uploadId + " for " + objectKey + ": "
                    + e.getMessage() + ". Parts may be left behind.");
        }
    }
}

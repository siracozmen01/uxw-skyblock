package com.uxplima.uxmskyblock.persistence.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.uxplima.uxmskyblock.persistence.storage.s3.S3HttpRequest;
import com.uxplima.uxmskyblock.persistence.storage.s3.S3HttpResponse;
import com.uxplima.uxmskyblock.persistence.storage.s3.S3HttpTransport;

/**
 * Deterministic in-memory HTTP transport simulating S3/R2 protocol endpoints for contract verification.
 */
public class MockS3HttpTransport implements S3HttpTransport {

    private static final Pattern PART_NUMBER_PATTERN = Pattern.compile("partNumber=(\\d+)");
    private static final Pattern UPLOAD_ID_PATTERN = Pattern.compile("uploadId=([^&]+)");

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<String>>> objectHeaders = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, byte[]>> multipartUploads = new ConcurrentHashMap<>();
    private final List<String> abortedUploads = Collections.synchronizedList(new ArrayList<>());
    private final List<S3HttpRequest> recordedRequests = Collections.synchronizedList(new ArrayList<>());

    private final AtomicInteger uploadIdSeq = new AtomicInteger(1000);
    private final AtomicBoolean failPartUpload = new AtomicBoolean(false);
    private int failPartNumber = -1;

    public void setFailOnPartUpload(int partNumber) {
        this.failPartUpload.set(true);
        this.failPartNumber = partNumber;
    }

    public List<S3HttpRequest> recordedRequests() {
        return List.copyOf(recordedRequests);
    }

    public List<String> abortedUploads() {
        return List.copyOf(abortedUploads);
    }

    public Map<String, byte[]> objects() {
        return Collections.unmodifiableMap(objects);
    }

    public boolean hasOrphanedMultipartUploads() {
        return !multipartUploads.isEmpty();
    }

    @Override
    public S3HttpResponse send(S3HttpRequest request) throws IOException, InterruptedException {
        recordedRequests.add(request);
        URI uri = request.uri();
        String path = uri.getPath();
        String query = uri.getQuery();
        String method = request.method();

        if ("PUT".equalsIgnoreCase(method)) {
            if (query != null && query.contains("partNumber=")) {
                // UploadPart
                Matcher partMatcher = PART_NUMBER_PATTERN.matcher(query);
                Matcher idMatcher = UPLOAD_ID_PATTERN.matcher(query);
                if (partMatcher.find() && idMatcher.find()) {
                    int partNum = Integer.parseInt(partMatcher.group(1));
                    String uploadId = idMatcher.group(1);

                    if (failPartUpload.get() && partNum == failPartNumber) {
                        throw new IOException("Simulated network failure on part " + partNum);
                    }

                    byte[] body = request.body();
                    if (body.length == 0 && request.bodyStream() != null) {
                        body = request.bodyStream().readAllBytes();
                    }

                    Map<Integer, byte[]> parts = multipartUploads.get(uploadId);
                    if (parts == null) {
                        return new S3HttpResponse(404, Map.of(), new ByteArrayInputStream(new byte[0]));
                    }
                    parts.put(partNum, body);
                    String etag = "\"etag-" + partNum + "\"";
                    return new S3HttpResponse(
                            200, Map.of("ETag", List.of(etag)), new ByteArrayInputStream(new byte[0]));
                }
            }

            // Normal PutObject
            byte[] body = request.body();
            if (body.length == 0 && request.bodyStream() != null) {
                body = request.bodyStream().readAllBytes();
            }
            objects.put(path, body);
            Map<String, List<String>> headers = new LinkedHashMap<>();
            headers.put("ETag", List.of("\"etag-full\""));
            request.headers().forEach((k, v) -> {
                if (k.toLowerCase(java.util.Locale.ROOT).startsWith("x-amz-meta-")) {
                    headers.put(k.toLowerCase(java.util.Locale.ROOT), v);
                }
            });
            objectHeaders.put(path, headers);
            return new S3HttpResponse(200, headers, new ByteArrayInputStream(new byte[0]));
        }

        if ("POST".equalsIgnoreCase(method)) {
            if (query != null && query.contains("uploads")) {
                // InitiateMultipartUpload
                String uploadId = "upload-" + uploadIdSeq.incrementAndGet();
                multipartUploads.put(uploadId, new ConcurrentHashMap<>());

                String xml = "<InitiateMultipartUploadResult>"
                        + "<Bucket>bucket</Bucket>"
                        + "<Key>" + path + "</Key>"
                        + "<UploadId>" + uploadId + "</UploadId>"
                        + "</InitiateMultipartUploadResult>";

                return new S3HttpResponse(
                        200,
                        Map.of("Content-Type", List.of("application/xml")),
                        new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            }

            if (query != null && query.contains("uploadId=")) {
                // CompleteMultipartUpload
                Matcher idMatcher = UPLOAD_ID_PATTERN.matcher(query);
                if (idMatcher.find()) {
                    String uploadId = idMatcher.group(1);
                    Map<Integer, byte[]> parts = multipartUploads.remove(uploadId);
                    if (parts == null) {
                        return new S3HttpResponse(404, Map.of(), new ByteArrayInputStream(new byte[0]));
                    }

                    ByteArrayOutputStream full = new ByteArrayOutputStream();
                    List<Integer> sortedPartNums = new ArrayList<>(parts.keySet());
                    Collections.sort(sortedPartNums);
                    for (int p : sortedPartNums) {
                        full.write(parts.get(p));
                    }
                    objects.put(path, full.toByteArray());

                    String xml = "<CompleteMultipartUploadResult>"
                            + "<Location>https://s3.amazonaws.com" + path + "</Location>"
                            + "<Bucket>bucket</Bucket>"
                            + "<Key>" + path + "</Key>"
                            + "<ETag>\"composite-etag\"</ETag>"
                            + "</CompleteMultipartUploadResult>";
                    return new S3HttpResponse(
                            200,
                            Map.of("Content-Type", List.of("application/xml")),
                            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
                }
            }
        }

        if ("DELETE".equalsIgnoreCase(method)) {
            if (query != null && query.contains("uploadId=")) {
                // AbortMultipartUpload
                Matcher idMatcher = UPLOAD_ID_PATTERN.matcher(query);
                if (idMatcher.find()) {
                    String uploadId = idMatcher.group(1);
                    multipartUploads.remove(uploadId);
                    abortedUploads.add(uploadId);
                    return new S3HttpResponse(204, Map.of(), new ByteArrayInputStream(new byte[0]));
                }
            }

            // Normal DeleteObject
            objects.remove(path);
            objectHeaders.remove(path);
            return new S3HttpResponse(204, Map.of(), new ByteArrayInputStream(new byte[0]));
        }

        if ("HEAD".equalsIgnoreCase(method)) {
            if (!objects.containsKey(path)) {
                return new S3HttpResponse(404, Map.of(), new ByteArrayInputStream(new byte[0]));
            }
            byte[] body = objects.get(path);
            Map<String, List<String>> headers = new LinkedHashMap<>(objectHeaders.getOrDefault(path, Map.of()));
            headers.put("Content-Length", List.of(String.valueOf(body.length)));
            headers.put("Content-Type", List.of("application/octet-stream"));
            headers.put(
                    "Last-Modified",
                    List.of(DateTimeFormatter.RFC_1123_DATE_TIME.format(
                            Instant.now().atZone(java.time.ZoneOffset.UTC))));
            return new S3HttpResponse(200, headers, new ByteArrayInputStream(new byte[0]));
        }

        if ("GET".equalsIgnoreCase(method)) {
            if (query != null && query.contains("list-type=2")) {
                // ListObjectsV2
                StringBuilder xml = new StringBuilder("<ListBucketResult><Contents>");
                for (String key : objects.keySet()) {
                    xml.append("<Key>")
                            .append(key.startsWith("/") ? key.substring(1) : key)
                            .append("</Key>");
                }
                xml.append("</Contents></ListBucketResult>");
                return new S3HttpResponse(
                        200,
                        Map.of("Content-Type", List.of("application/xml")),
                        new ByteArrayInputStream(xml.toString().getBytes(StandardCharsets.UTF_8)));
            }

            if (!objects.containsKey(path)) {
                return new S3HttpResponse(404, Map.of(), new ByteArrayInputStream(new byte[0]));
            }
            byte[] body = objects.get(path);
            return new S3HttpResponse(200, objectHeaders.getOrDefault(path, Map.of()), new ByteArrayInputStream(body));
        }

        return new S3HttpResponse(400, Map.of(), new ByteArrayInputStream(new byte[0]));
    }
}

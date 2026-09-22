package com.uxplima.uxmskyblock.persistence.storage.s3;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.uxplima.uxmskyblock.core.domain.storage.S3Credentials;

/**
 * Standard AWS Signature Version 4 (SigV4) request signer for S3 and Cloudflare R2 endpoints.
 */
public final class AwsSigV4Signer {

    private static final String SCHEME = "AWS4";
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String TERMINATOR = "aws4_request";
    private static final String SERVICE = "s3";

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private AwsSigV4Signer() {}

    /**
     * Signs an HTTP request using SigV4 and returns a new {@link S3HttpRequest} with authentication headers attached.
     *
     * <p>The URI is signed exactly as it will be sent. Whoever builds it owns the encoding, and it
     * has to be the encoding that goes on the wire: a signature computed over a different string
     * than the one the provider receives is a 403 at runtime rather than a mistake the compiler can
     * catch. {@link #rfc3986Encode} is the encoder to use, and {@code S3Requests} does.
     *
     * @param request original request
     * @param credentials provider credentials
     * @param region target region (or "auto" for Cloudflare R2)
     * @param timestamp timestamp for signing
     * @return signed request
     */
    public static S3HttpRequest sign(
            S3HttpRequest request, S3Credentials credentials, String region, Instant timestamp) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(credentials, "credentials must not be null");
        Objects.requireNonNull(region, "region must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");

        String dateTimeStamp = DATE_TIME_FORMATTER.format(timestamp);
        String dateStamp = DATE_FORMATTER.format(timestamp);

        // Mutable headers map
        Map<String, List<String>> signedHeadersMap = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
            signedHeadersMap.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        // 1. Ensure Host header
        URI uri = request.uri();
        String host = uri.getHost();
        if (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443) {
            host = host + ":" + uri.getPort();
        }
        signedHeadersMap.put("Host", List.of(host));

        // 2. Ensure x-amz-date
        signedHeadersMap.put("x-amz-date", List.of(dateTimeStamp));

        // 3. Compute and attach payload hash (x-amz-content-sha256)
        String payloadHash;
        List<String> existingPayloadHash = signedHeadersMap.get("x-amz-content-sha256");
        if (existingPayloadHash != null && !existingPayloadHash.isEmpty()) {
            payloadHash = existingPayloadHash.get(0);
        } else {
            payloadHash = sha256Hex(request.body());
            signedHeadersMap.put("x-amz-content-sha256", List.of(payloadHash));
        }

        // 4. Attach session token if present
        if (credentials.sessionToken() != null && !credentials.sessionToken().isBlank()) {
            signedHeadersMap.put("x-amz-security-token", List.of(credentials.sessionToken()));
        }

        // 5. Canonical URI
        //
        // The path is taken exactly as it will go on the wire. It used to be encoded again here,
        // which turned the %20 of a key with a space into %2520: the request went to one path and
        // the signature was computed over another, and the provider answered SignatureDoesNotMatch
        // for every key with a space, a plus or a comma in it. Whoever builds the URI owns the
        // encoding, and S3Requests does it with rfc3986Encode so the two are the same string.
        String canonicalUri = canonicalPathOf(uri);

        // 6. Canonical Query String, sorted but not encoded again, for the same reason.
        String canonicalQuery = buildCanonicalQueryString(uri.getRawQuery());

        // 7. Canonical Headers and SignedHeaders
        Map<String, String> sortedHeaders = new TreeMap<>();
        for (Map.Entry<String, List<String>> entry : signedHeadersMap.entrySet()) {
            String lowerKey = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            String value = String.join(",", entry.getValue()).trim().replaceAll("\\s+", " ");
            sortedHeaders.put(lowerKey, value);
        }

        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        for (Map.Entry<String, String> entry : sortedHeaders.entrySet()) {
            canonicalHeaders
                    .append(entry.getKey())
                    .append(':')
                    .append(entry.getValue())
                    .append('\n');
            if (signedHeaders.length() > 0) {
                signedHeaders.append(';');
            }
            signedHeaders.append(entry.getKey());
        }

        // 8. Canonical Request
        String canonicalRequest = request.method() + "\n"
                + canonicalUri + "\n"
                + canonicalQuery + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;

        // 9. String to Sign
        String credentialScope = dateStamp + "/" + region + "/" + SERVICE + "/" + TERMINATOR;
        String stringToSign = ALGORITHM + "\n"
                + dateTimeStamp + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        // 10. Signing Key
        byte[] kSigning = getSignatureKey(credentials.secretKey(), dateStamp, region, SERVICE);

        // 11. Calculate Signature
        byte[] signatureBytes = hmacSha256(kSigning, stringToSign);
        String signature = HexFormat.of().formatHex(signatureBytes);

        // 12. Build Authorization Header
        String authorization = ALGORITHM + " Credential="
                + credentials.accessKey() + "/"
                + credentialScope + ", SignedHeaders="
                + signedHeaders + ", Signature="
                + signature;

        signedHeadersMap.put("Authorization", List.of(authorization));

        return new S3HttpRequest(
                request.method(),
                request.uri(),
                Collections.unmodifiableMap(signedHeadersMap),
                request.body(),
                request.bodyStream(),
                request.contentLength());
    }

    private static String buildCanonicalQueryString(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }

        Map<String, String> sortedParams = new TreeMap<>();
        int start = 0;
        int len = rawQuery.length();
        while (start < len) {
            int nextAmp = rawQuery.indexOf('&', start);
            int end = nextAmp == -1 ? len : nextAmp;
            String pair = rawQuery.substring(start, end);
            if (!pair.isEmpty()) {
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    sortedParams.put(pair.substring(0, idx), pair.substring(idx + 1));
                } else {
                    sortedParams.put(pair, "");
                }
            }
            if (nextAmp == -1) {
                break;
            }
            start = nextAmp + 1;
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sortedParams.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    /**
     * The path this request will be signed over.
     *
     * <p>It is the path exactly as it will go on the wire. Anything else is a signature covering a
     * string the provider never sees, which comes back as a 403 rather than as a mistake anybody
     * can see in the code, so this is worth being able to assert on its own.
     */
    static String canonicalPathOf(URI uri) {
        String path = uri.getRawPath();
        return path == null || path.isEmpty() ? "/" : path;
    }

    public static String rfc3986Encode(String value, boolean path) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder encoded = new StringBuilder();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            char c = (char) (b & 0xFF);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_'
                    || c == '-'
                    || c == '~'
                    || c == '.'
                    || (path && c == '/')) {
                encoded.append(c);
            } else {
                encoded.append(String.format("%%%02X", b & 0xFF));
            }
        }
        return encoded.toString();
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256", e);
        }
    }

    private static byte[] getSignatureKey(String key, String dateStamp, String regionName, String serviceName) {
        byte[] kSecret = (SCHEME + key).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, dateStamp);
        byte[] kRegion = hmacSha256(kDate, regionName);
        byte[] kService = hmacSha256(kRegion, serviceName);
        return hmacSha256(kService, TERMINATOR);
    }
}

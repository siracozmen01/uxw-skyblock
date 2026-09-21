package com.uxplima.uxmskyblock.persistence.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.uxplima.uxmskyblock.core.domain.storage.S3Credentials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The signature this plugin sends is the one AWS expects.
 *
 * <p>Every call to an object store goes out signed, and a signature that is wrong in any detail
 * comes back 403 with no explanation of which detail. Backups then fail on a live server and the
 * only clue is a status code. The signer shipped with no test at all.
 *
 * <p>The vector below is the one in AWS's own SigV4 documentation, "Example: GET Object", with its
 * published credentials, its published timestamp and its published signature. Nothing here is a
 * value this project chose, which is the point: an assertion written against our own output would
 * only prove the code still does what it did.
 */
class AwsSigV4SignerTest {

    private static final S3Credentials EXAMPLE_CREDENTIALS =
            new S3Credentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", null);

    private static final Instant EXAMPLE_TIME = Instant.parse("2013-05-24T00:00:00Z");

    private static final String EXPECTED_SIGNATURE = "f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41";

    private static String authorizationOf(S3HttpRequest signed) {
        return headerOf(signed, "Authorization");
    }

    /** One header value, and a clear failure rather than a null when the signer left it out. */
    private static String headerOf(S3HttpRequest signed, String name) {
        return signed.headers().getOrDefault(name, List.of()).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("the signer must attach " + name));
    }

    @Test
    @DisplayName("AWS's own GET Object example produces AWS's own signature")
    void theDocumentedExampleMatches() {
        S3HttpRequest request = S3HttpRequest.of(
                "GET",
                URI.create("https://examplebucket.s3.amazonaws.com/test.txt"),
                Map.of("Range", List.of("bytes=0-9")));

        S3HttpRequest signed = AwsSigV4Signer.sign(request, EXAMPLE_CREDENTIALS, "us-east-1", EXAMPLE_TIME);

        assertThat(authorizationOf(signed))
                .describedAs("a signature that differs anywhere comes back 403 with no clue which part was wrong")
                .contains("Signature=" + EXPECTED_SIGNATURE);
    }

    @Test
    @DisplayName("The example's signed header list is the one AWS names")
    void theSignedHeaderListMatches() {
        S3HttpRequest request = S3HttpRequest.of(
                "GET",
                URI.create("https://examplebucket.s3.amazonaws.com/test.txt"),
                Map.of("Range", List.of("bytes=0-9")));

        S3HttpRequest signed = AwsSigV4Signer.sign(request, EXAMPLE_CREDENTIALS, "us-east-1", EXAMPLE_TIME);

        assertThat(authorizationOf(signed)).contains("SignedHeaders=host;range;x-amz-content-sha256;x-amz-date");
    }

    @Test
    @DisplayName("The credential scope carries the date, the region and the service")
    void theCredentialScopeIsRight() {
        S3HttpRequest signed = AwsSigV4Signer.sign(
                S3HttpRequest.of("GET", URI.create("https://examplebucket.s3.amazonaws.com/test.txt"), Map.of()),
                EXAMPLE_CREDENTIALS,
                "us-east-1",
                EXAMPLE_TIME);

        assertThat(authorizationOf(signed))
                .contains("Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request");
    }

    @Test
    @DisplayName("An empty body is hashed, not left out")
    void anEmptyBodyIsStillHashed() {
        S3HttpRequest signed = AwsSigV4Signer.sign(
                S3HttpRequest.of("GET", URI.create("https://examplebucket.s3.amazonaws.com/test.txt"), Map.of()),
                EXAMPLE_CREDENTIALS,
                "us-east-1",
                EXAMPLE_TIME);

        assertThat(headerOf(signed, "x-amz-content-sha256"))
                .describedAs("the SHA-256 of nothing, which S3 checks against the body it received")
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    @DisplayName("A different secret gives a different signature")
    void theSecretMatters() {
        S3HttpRequest request =
                S3HttpRequest.of("GET", URI.create("https://examplebucket.s3.amazonaws.com/test.txt"), Map.of());

        String first = authorizationOf(AwsSigV4Signer.sign(request, EXAMPLE_CREDENTIALS, "us-east-1", EXAMPLE_TIME));
        String second = authorizationOf(AwsSigV4Signer.sign(
                request,
                new S3Credentials("AKIAIOSFODNN7EXAMPLE", "a-different-secret-entirely", null),
                "us-east-1",
                EXAMPLE_TIME));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("A session token is signed, so a temporary credential works")
    void aSessionTokenIsSigned() {
        S3HttpRequest signed = AwsSigV4Signer.sign(
                S3HttpRequest.of("GET", URI.create("https://examplebucket.s3.amazonaws.com/test.txt"), Map.of()),
                new S3Credentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "a-token"),
                "us-east-1",
                EXAMPLE_TIME);

        assertThat(headerOf(signed, "x-amz-security-token")).isEqualTo("a-token");
        assertThat(authorizationOf(signed))
                .describedAs("a token attached but not signed is a 403 on every temporary credential")
                .contains("x-amz-security-token");
    }

    @Test
    @DisplayName("Signing the same request twice at the same moment gives the same signature")
    void signingIsDeterministic() {
        S3HttpRequest request = S3HttpRequest.of(
                "PUT",
                URI.create("https://examplebucket.s3.amazonaws.com/backups/set-1/manifest.json"),
                Map.of("Content-Type", List.of("application/json")),
                "{\"a\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(authorizationOf(AwsSigV4Signer.sign(request, EXAMPLE_CREDENTIALS, "auto", EXAMPLE_TIME)))
                .isEqualTo(authorizationOf(AwsSigV4Signer.sign(request, EXAMPLE_CREDENTIALS, "auto", EXAMPLE_TIME)));
    }
}

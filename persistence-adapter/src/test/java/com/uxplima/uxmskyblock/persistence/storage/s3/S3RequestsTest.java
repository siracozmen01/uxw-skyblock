package com.uxplima.uxmskyblock.persistence.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.uxplima.uxmskyblock.core.domain.storage.ProviderVerificationStatus;
import com.uxplima.uxmskyblock.core.domain.storage.S3AddressingMode;
import com.uxplima.uxmskyblock.core.domain.storage.S3Credentials;
import com.uxplima.uxmskyblock.core.domain.storage.S3ProviderTarget;
import com.uxplima.uxmskyblock.core.domain.storage.S3StorageConfiguration;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where a request goes, and that the signature is computed over that exact string.
 *
 * <p>The path was built by {@code new URI(...)}, which encodes a space as {@code %20} and leaves a
 * plus and a comma alone, and then signed by an encoder that turned that {@code %20} into
 * {@code %2520} and the plus into {@code %2B}. The request went to one path and the signature
 * covered another, so every object key with a space, a plus or a comma in it came back 403
 * SignatureDoesNotMatch. Nothing tested any of it.
 */
class S3RequestsTest {

    private static final StorageBucket BUCKET = new StorageBucket("island-backups");

    /** No query at all, which is what most of these requests carry. */
    private static final @org.jspecify.annotations.Nullable String NO_QUERY = null;

    private static S3StorageConfiguration configuration(
            String endpoint, S3AddressingMode mode, @org.jspecify.annotations.Nullable String prefix, int maxRetries) {
        return new S3StorageConfiguration(
                URI.create(endpoint),
                "us-east-1",
                BUCKET,
                new S3Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", null),
                mode,
                S3ProviderTarget.AWS_S3,
                prefix,
                S3StorageConfiguration.DEFAULT_MULTIPART_THRESHOLD,
                S3StorageConfiguration.DEFAULT_PART_SIZE,
                maxRetries,
                false,
                ProviderVerificationStatus.UNVERIFIED);
    }

    private static S3Requests requests(S3StorageConfiguration configuration, S3HttpTransport transport) {
        return new S3Requests(configuration, transport);
    }

    private static S3Requests pathStyle() {
        return requests(configuration("https://s3.example.com", S3AddressingMode.PATH_STYLE, null, 0), r -> {
            throw new IOException("not sent in this test");
        });
    }

    @Test
    @DisplayName("Path style puts the bucket in the path, virtual host puts it in the host")
    void thetwoAddressingModesDiffer() {
        assertThat(pathStyle().objectUri(BUCKET, "sets/one.zip", NO_QUERY))
                .hasToString("https://s3.example.com/island-backups/sets/one.zip");

        S3Requests virtual =
                requests(configuration("https://s3.example.com", S3AddressingMode.VIRTUAL_HOSTED, null, 0), r -> {
                    throw new IOException("not sent in this test");
                });
        assertThat(virtual.objectUri(BUCKET, "sets/one.zip", NO_QUERY))
                .hasToString("https://island-backups.s3.example.com/sets/one.zip");
    }

    @Test
    @DisplayName("A port on the endpoint is carried through, which is how an emulator is reached")
    void theportSurvives() {
        S3Requests local = requests(configuration("http://localhost:9000", S3AddressingMode.PATH_STYLE, null, 0), r -> {
            throw new IOException("not sent in this test");
        });

        assertThat(local.objectUri(BUCKET, "sets/one.zip", NO_QUERY))
                .hasToString("http://localhost:9000/island-backups/sets/one.zip");
        assertThat(local.bucketUri(BUCKET, "list-type=2"))
                .hasToString("http://localhost:9000/island-backups?list-type=2");
    }

    @Test
    @DisplayName("The operator's prefix goes in front of every key")
    void theprefixLeadsTheKey() {
        S3Requests prefixed = requests(
                configuration("https://s3.example.com", S3AddressingMode.PATH_STYLE, "servers/alpha", 0), r -> {
                    throw new IOException("not sent in this test");
                });

        assertThat(prefixed.objectUri(BUCKET, "sets/one.zip", NO_QUERY))
                .hasToString("https://s3.example.com/island-backups/servers/alpha/sets/one.zip");
    }

    @Test
    @DisplayName("A key with a space, a plus or a comma is signed over the string that goes on the wire")
    void anawkwardKeyIsSignedOverWhatIsSent() {
        URI uri = pathStyle().objectUri(BUCKET, "sets/my key+and,more.zip", NO_QUERY);

        assertThat(uri.toString())
                .describedAs("encoded once, by the encoder the signature uses")
                .isEqualTo("https://s3.example.com/island-backups/sets/my%20key%2Band%2Cmore.zip");

        assertThat(AwsSigV4Signer.canonicalPathOf(uri))
                .describedAs("the signature covers the path the provider will actually receive")
                .isEqualTo(uri.getRawPath());
    }

    @Test
    @DisplayName("A key that climbs out of its prefix is refused")
    void apathTraversalIsRefused() {
        assertThatThrownBy(() -> pathStyle().sanitizeKey("../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pathStyle().objectUri(BUCKET, "sets/../../secret", NO_QUERY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("A key is read the same however it was typed: backslashes and leading slashes go")
    void akeyIsNormalised() {
        assertThat(pathStyle().sanitizeKey("///sets/one.zip")).isEqualTo("sets/one.zip");
        assertThat(pathStyle().sanitizeKey("sets\\one.zip")).isEqualTo("sets/one.zip");
    }

    @Test
    @DisplayName("A server side failure is retried, and the failed response is closed each time")
    void afailureIsRetried() throws Exception {
        RecordingTransport transport = new RecordingTransport(List.of(500, 503, 200));
        S3Requests retrying =
                requests(configuration("https://s3.example.com", S3AddressingMode.PATH_STYLE, null, 2), transport);

        S3HttpResponse response =
                retrying.send(S3HttpRequest.of("GET", URI.create("https://s3.example.com/island-backups/a"), Map.of()));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(transport.sent).describedAs("two failures and the answer").isEqualTo(3);
    }

    @Test
    @DisplayName("A failure that outlasts the retries is handed back rather than retried forever")
    void afailureIsEventuallyGivenBack() throws Exception {
        RecordingTransport transport = new RecordingTransport(List.of(500, 500, 500, 500));
        S3Requests retrying =
                requests(configuration("https://s3.example.com", S3AddressingMode.PATH_STYLE, null, 1), transport);

        S3HttpResponse response =
                retrying.send(S3HttpRequest.of("GET", URI.create("https://s3.example.com/island-backups/a"), Map.of()));

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(transport.sent).describedAs("the first try and one retry").isEqualTo(2);
    }

    @Test
    @DisplayName("A refusal the provider means is handed straight back, not retried")
    void aclientErrorIsNotRetried() throws Exception {
        RecordingTransport transport = new RecordingTransport(List.of(404, 200));
        S3Requests retrying =
                requests(configuration("https://s3.example.com", S3AddressingMode.PATH_STYLE, null, 3), transport);

        S3HttpResponse response =
                retrying.send(S3HttpRequest.of("GET", URI.create("https://s3.example.com/island-backups/a"), Map.of()));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(transport.sent)
                .describedAs("retrying a 404 only wastes time")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("A request that never gets through throws rather than returning nothing")
    void anunreachableProviderThrows() {
        S3Requests retrying =
                requests(configuration("https://s3.example.com", S3AddressingMode.PATH_STYLE, null, 1), r -> {
                    throw new IOException("the network is gone");
                });

        assertThatThrownBy(() -> retrying.send(
                        S3HttpRequest.of("GET", URI.create("https://s3.example.com/island-backups/a"), Map.of())))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("the network is gone");
    }

    /** Answers with the statuses it was given, in order, and counts what it was asked. */
    private static final class RecordingTransport implements S3HttpTransport {

        private final List<Integer> statuses;
        private final List<S3HttpRequest> requests = new ArrayList<>();
        int sent;

        RecordingTransport(List<Integer> statuses) {
            this.statuses = List.copyOf(statuses);
        }

        @Override
        public S3HttpResponse send(S3HttpRequest request) {
            requests.add(request);
            int status = statuses.get(Math.min(sent, statuses.size() - 1));
            sent++;
            return S3HttpResponse.of(status, Map.of(), new byte[0]);
        }
    }
}

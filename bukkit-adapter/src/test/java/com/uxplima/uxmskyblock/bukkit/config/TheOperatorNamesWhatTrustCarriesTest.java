package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.uxplima.uxmskyblock.core.domain.permission.StandardPermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * What a trust grant carries is a list in the operator's file.
 *
 * <p>The grant subsystem could not make a grant at all until {@code /is trust} existed, and the
 * permissions it hands over must never be a list written in the code: an operator decides what a
 * trusted visitor may do on an island.
 */
class TheOperatorNamesWhatTrustCarriesTest {

    private static final Path CONF = Path.of("src/main/resources/modules/temporary-access.conf");

    private static TemporaryAccessConfiguration shipped() throws Exception {
        ConfigurationNode root = HoconConfigurationLoader.builder()
                .source(() -> Files.newBufferedReader(CONF, StandardCharsets.UTF_8))
                .build()
                .load();
        return TemporaryAccessConfiguration.load(root);
    }

    @Test
    @DisplayName("The shipped file names the list, and it is what the configuration carries")
    void theShippedFileNamesTheList() throws Exception {
        assertThat(Files.readString(CONF, StandardCharsets.UTF_8))
                .describedAs("a list an operator cannot see is a list they cannot change")
                .contains("trust-permissions");

        assertThat(shipped().trustPermissions())
                .contains(StandardPermissions.BLOCK_BREAK, StandardPermissions.BLOCK_PLACE)
                .isEqualTo(TemporaryAccessConfiguration.DEFAULT_TRUST_PERMISSIONS);
    }

    @Test
    @DisplayName("A trust grant never carries management, membership or money")
    void trustIsNeverMembership() throws Exception {
        assertThat(shipped().trustPermissions())
                .describedAs("a trusted visitor is never a member")
                .doesNotContain(
                        StandardPermissions.SETTINGS_MODIFY,
                        StandardPermissions.MEMBER_INVITE,
                        StandardPermissions.MEMBER_KICK,
                        StandardPermissions.MEMBER_PROMOTE,
                        StandardPermissions.MEMBER_DEMOTE,
                        StandardPermissions.BANK_DEPOSIT,
                        StandardPermissions.BANK_WITHDRAW,
                        StandardPermissions.SHOP_ACCESS);
    }

    @Test
    @DisplayName("A key the operator mistyped is skipped and the rest are kept")
    void amistypedKeyIsSkipped() throws Exception {
        String hocon = """
                temporary-access {
                    trust-permissions = [ "uxm:block.break", "not a permission key", "uxm:block.place" ]
                }
                """;
        ConfigurationNode root = HoconConfigurationLoader.builder()
                .source(() -> new java.io.BufferedReader(new java.io.StringReader(hocon)))
                .build()
                .load();

        assertThat(TemporaryAccessConfiguration.load(root).trustPermissions())
                .describedAs("a server that will not start over one line is worse than one that starts")
                .containsExactlyInAnyOrder(StandardPermissions.BLOCK_BREAK, StandardPermissions.BLOCK_PLACE);
    }

    @Test
    @DisplayName("An empty list falls back to the shipped one rather than granting nothing")
    void anEmptyListFallsBack() throws Exception {
        String hocon = """
                temporary-access {
                    trust-permissions = []
                }
                """;
        ConfigurationNode root = HoconConfigurationLoader.builder()
                .source(() -> new java.io.BufferedReader(new java.io.StringReader(hocon)))
                .build()
                .load();

        assertThat(TemporaryAccessConfiguration.load(root).trustPermissions())
                .describedAs("a grant that lets the guest do nothing looks like the feature is broken")
                .isEqualTo(TemporaryAccessConfiguration.DEFAULT_TRUST_PERMISSIONS);
    }
}

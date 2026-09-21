package com.uxplima.uxmskyblock.bukkit.config;

import java.util.Locale;
import java.util.Objects;

import com.uxplima.uxmskyblock.rest.config.RestConfiguration;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Whether this node serves the embedded REST API, and on what.
 *
 * <p>It ships off. An HTTP surface that opens itself on first boot, with a token that is the same
 * on every copy of the plugin, is a hole rather than a feature. The operator turns it on and sets
 * the token, and the token is refused if it is still the shipped one.
 */
public record RestApiConfiguration(boolean enabled, String host, int port, String bearerToken) {

    /** The token the file ships with. A node that is enabled while still holding it does not start. */
    public static final String UNSET_TOKEN = "change-me";

    public RestApiConfiguration {
        Objects.requireNonNull(host, "host must not be null");
        Objects.requireNonNull(bearerToken, "bearerToken must not be null");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("rest.port must be between 0 and 65535: " + port);
        }
    }

    public static RestApiConfiguration defaults() {
        return new RestApiConfiguration(false, "127.0.0.1", 8080, UNSET_TOKEN);
    }

    public static RestApiConfiguration load(@Nullable ConfigurationNode rootNode) {
        if (rootNode == null) {
            return defaults();
        }
        ConfigurationNode rest = rootNode.node("rest");
        boolean enabled = rest.node("enabled").getBoolean(false);
        String host = rest.node("host").getString("127.0.0.1");
        int port = rest.node("port").getInt(8080);
        String token = rest.node("bearer-token").getString(UNSET_TOKEN);
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        if (token == null) {
            token = UNSET_TOKEN;
        }
        return new RestApiConfiguration(enabled, host.trim(), port, token.trim());
    }

    /**
     * Whether this node should actually open the port. Enabled with the shipped token is refused,
     * because that token is public and the same everywhere.
     */
    public boolean shouldStart() {
        return enabled && !bearerToken.isBlank() && !bearerToken.equalsIgnoreCase(UNSET_TOKEN);
    }

    /** True when the operator asked for the API but left the shipped token in place. */
    public boolean refusedForDefaultToken() {
        return enabled && !shouldStart();
    }

    /** The shape the REST module reads, which knows nothing of this plugin's configuration file. */
    public RestConfiguration toRestConfiguration() {
        return new RestConfiguration(enabled, host.toLowerCase(Locale.ROOT), port, bearerToken);
    }
}

package com.uxplima.uxmskyblock.bukkit.bedrock;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.bukkit.Server;

import com.uxplima.uxmlib.bedrock.BedrockDetector;
import org.jspecify.annotations.Nullable;

/**
 * A Bedrock detector that keeps asking until floodgate or Geyser answers.
 *
 * <p>The library's detector is chosen once, by asking whether floodgate is enabled. Asked while the
 * menus were built, that answer was no whenever floodgate had not enabled yet, and it stayed no: every
 * Bedrock player got Java windows for as long as the server ran. This asks again on every call until
 * a real detector turns up, and keeps that one.
 */
public final class LateBedrockDetector implements BedrockDetector {

    private final Supplier<BedrockDetector> resolver;
    private volatile @Nullable BedrockDetector found;

    public LateBedrockDetector(Supplier<BedrockDetector> resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    }

    /** The detector for this server, resolved when it is first asked after floodgate or Geyser enables. */
    public static LateBedrockDetector forServer(Server server) {
        Objects.requireNonNull(server, "server must not be null");
        return new LateBedrockDetector(() -> BedrockDetector.forServer(server));
    }

    private BedrockDetector current() {
        BedrockDetector known = found;
        if (known != null) {
            return known;
        }
        BedrockDetector asked = resolver.get();
        if (asked != BedrockDetector.NONE) {
            found = asked;
        }
        return asked;
    }

    @Override
    public boolean isBedrock(UUID player) {
        return current().isBedrock(player);
    }

    @Override
    public Optional<String> xuid(UUID player) {
        return current().xuid(player);
    }

    @Override
    public String backend() {
        return current().backend();
    }
}

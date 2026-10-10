package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.Bukkit;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.name.IslandNameService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.jspecify.annotations.Nullable;

/**
 * How an island is named to a player: the name it was given, or whose island it is.
 *
 * <p>The leaderboard, the allies, the alliance offers and the bookmarks named an island nobody had
 * named by its id, so a player read {@code 91ec264e-53eb-49a1-85f6-2c0c5b876177} where they wanted to
 * know whose island it was. The short id is left only for an island the server cannot find.
 *
 * <p>Every answer reads storage, so it is asked off the player's thread.
 */
final class IslandLabels {

    private final IslandLocationService locations;
    private final Messages messages;
    private volatile Supplier<@Nullable IslandNameService> names = () -> null;

    IslandLabels(IslandLocationService locations, Messages messages) {
        this.locations = Objects.requireNonNull(locations, "locations must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    /** Where the names islands were given are read. */
    void useNames(Supplier<@Nullable IslandNameService> names) {
        this.names = Objects.requireNonNull(names, "names must not be null");
    }

    Component of(Audience reader, IslandId island) {
        IslandNameService service = names.get();
        if (service != null) {
            Optional<String> named = service.getIslandName(island).map(name -> name.value());
            if (named.isPresent()) {
                return Component.text(named.get());
            }
        }
        Optional<String> owner = locations
                .findIsland(island)
                .map(found ->
                        Bukkit.getOfflinePlayer(found.ownerPlayerUuid().value()).getName());
        if (owner.isPresent()) {
            return messages.renderPlain(reader, "leaderboard.owned", Placeholder.unparsed("owner", owner.get()));
        }
        return messages.renderPlain(
                reader,
                "leaderboard.unnamed",
                Placeholder.unparsed("id", island.value().toString().substring(0, 8)));
    }
}

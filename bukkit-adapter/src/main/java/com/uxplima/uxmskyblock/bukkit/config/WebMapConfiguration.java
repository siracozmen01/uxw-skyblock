package com.uxplima.uxmskyblock.bukkit.config;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.webmap.MarkerLook;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * {@code modules/webmap.conf}: what Dynmap, BlueMap and Pl3xMap show of the islands.
 *
 * <p>The layer name, the label of an island and the colours of its area were written in code, so a
 * server could not translate its own map or draw it in its own palette.
 */
public record WebMapConfiguration(String layer, MarkerLook look) {

    public static final String DEFAULT_LAYER = "Islands";

    public WebMapConfiguration {
        Objects.requireNonNull(layer, "layer must not be null");
        Objects.requireNonNull(look, "look must not be null");
    }

    public static WebMapConfiguration defaultConfiguration() {
        return new WebMapConfiguration(DEFAULT_LAYER, MarkerLook.palette());
    }

    public static WebMapConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("webmap");
        if (node.virtual() || node.empty()) {
            return defaultConfiguration();
        }
        MarkerLook shipped = MarkerLook.palette();
        MarkerLook.Words words = shipped.words();
        ConfigurationNode said = node.node("words");
        return new WebMapConfiguration(
                node.node("layer").getString(DEFAULT_LAYER),
                new MarkerLook(
                        shade(node.node("top"), shipped.top()),
                        shade(node.node("allied"), shipped.allied()),
                        shade(node.node("other"), shipped.other()),
                        new MarkerLook.Words(
                                said.node("name").getString(words.name()),
                                said.node("owner").getString(words.owner()),
                                said.node("level").getString(words.level()),
                                said.node("rank").getString(words.rank()),
                                said.node("unranked").getString(words.unranked()),
                                said.node("worth").getString(words.worth()),
                                said.node("bank").getString(words.bank()))));
    }

    private static MarkerLook.Shade shade(ConfigurationNode node, MarkerLook.Shade shipped) {
        return new MarkerLook.Shade(
                node.node("border").getString(shipped.border()),
                node.node("fill").getString(shipped.fill()),
                node.node("opacity").getDouble(shipped.opacity()),
                node.node("weight").getInt(shipped.weight()));
    }
}

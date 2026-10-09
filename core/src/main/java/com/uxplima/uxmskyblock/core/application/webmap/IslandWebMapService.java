package com.uxplima.uxmskyblock.core.application.webmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.webmap.WebMapMarker;
import org.jspecify.annotations.Nullable;

/**
 * Enterprise web map integration service preparing multi-layer polygon descriptors
 * and rich HTML tooltips for web map renderers (Section 2.42).
 */
public final class IslandWebMapService {

    private final MarkerLook look;

    /** A service drawing in the shipped palette. */
    public IslandWebMapService() {
        this(MarkerLook.palette());
    }

    /** A service drawing in the look an operator wrote. */
    public IslandWebMapService(MarkerLook look) {
        this.look = Objects.requireNonNull(look, "look must not be null");
    }

    /** The look this service draws in. */
    public MarkerLook look() {
        return look;
    }

    /**
     * Contextual metadata for generating an island's web map marker.
     */
    public record IslandMapContext(
            Island island,
            @Nullable String customName,
            long levelScore,
            long netWorthMinorUnits,
            long bankBalanceMinorUnits,
            int rank,
            boolean isAllied) {
        public IslandMapContext {
            Objects.requireNonNull(island, "island must not be null");
        }
    }

    /**
     * Generates web map markers for a collection of islands based on their rankings and alliance status.
     *
     * @param islandContexts list of island metadata contexts
     * @return list of rendered WebMapMarker polygon definitions
     */
    public List<WebMapMarker> generateMarkers(List<IslandMapContext> islandContexts) {
        Objects.requireNonNull(islandContexts, "islandContexts must not be null");

        List<WebMapMarker> markers = new ArrayList<>(islandContexts.size());

        for (IslandMapContext ctx : islandContexts) {
            Island island = ctx.island();
            IslandBounds bounds = island.bounds();
            String islandIdStr = island.id().value().toString();
            String markerId = "island_" + islandIdStr;

            String displayName = (ctx.customName() != null && !ctx.customName().isBlank())
                    ? ctx.customName()
                    : look.words().nameOf(islandIdStr);

            // The leaders first, then allies, then everyone else, each in the colour the operator gave it.
            MarkerLook.Shade shade =
                    ctx.rank() > 0 && ctx.rank() <= 10 ? look.top() : ctx.isAllied() ? look.allied() : look.other();
            String borderColor = shade.border();
            String fillColor = shade.fill();
            double fillOpacity = shade.opacity();
            int lineWeight = shade.weight();

            double netWorth = ctx.netWorthMinorUnits() / 100.0;
            double bank = ctx.bankBalanceMinorUnits() / 100.0;

            MarkerLook.Words words = look.words();
            String htmlTooltip = String.format(
                    "<div class=\"skyblock-tooltip\" style=\"padding:5px;font-family:sans-serif;\">"
                            + "<h3 style=\"margin:0 0 5px 0;\">%s</h3>"
                            + "<div><b>%s:</b> %s</div>"
                            + "<div><b>%s:</b> %,d</div>"
                            + "<div><b>%s:</b> %s</div>"
                            + "<div><b>%s:</b> $%,.2f</div>"
                            + "<div><b>%s:</b> $%,.2f</div>"
                            + "</div>",
                    escapeHtml(displayName),
                    escapeHtml(words.owner()),
                    island.ownerPlayerUuid().value().toString().substring(0, 8),
                    escapeHtml(words.level()),
                    ctx.levelScore(),
                    escapeHtml(words.rank()),
                    ctx.rank() > 0 ? "#" + ctx.rank() : escapeHtml(words.unranked()),
                    escapeHtml(words.worth()),
                    netWorth,
                    escapeHtml(words.bank()),
                    bank);

            markers.add(new WebMapMarker(
                    markerId,
                    islandIdStr,
                    displayName,
                    bounds.minX(),
                    bounds.minZ(),
                    bounds.maxX(),
                    bounds.maxZ(),
                    borderColor,
                    fillColor,
                    fillOpacity,
                    lineWeight,
                    htmlTooltip));
        }

        return markers;
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}

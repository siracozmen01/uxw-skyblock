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

    public static final String COLOR_GOLD_BORDER = "#FFD700";
    public static final String COLOR_GOLD_FILL = "#FFE87C";
    public static final String COLOR_DEFAULT_BORDER = "#00BFFF";
    public static final String COLOR_DEFAULT_FILL = "#E0F7FA";
    public static final String COLOR_ALLIANCE_BORDER = "#32CD32";
    public static final String COLOR_ALLIANCE_FILL = "#90EE90";

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
                    : "Island " + islandIdStr.substring(0, 8);

            String borderColor;
            String fillColor;
            double fillOpacity;
            int lineWeight;

            if (ctx.rank() > 0 && ctx.rank() <= 10) {
                // Top 10 Leaderboard: Gold highlights
                borderColor = COLOR_GOLD_BORDER;
                fillColor = COLOR_GOLD_FILL;
                fillOpacity = 0.35;
                lineWeight = 3;
            } else if (ctx.isAllied()) {
                // Allied territory: Green highlights
                borderColor = COLOR_ALLIANCE_BORDER;
                fillColor = COLOR_ALLIANCE_FILL;
                fillOpacity = 0.25;
                lineWeight = 2;
            } else {
                // Standard operational island
                borderColor = COLOR_DEFAULT_BORDER;
                fillColor = COLOR_DEFAULT_FILL;
                fillOpacity = 0.15;
                lineWeight = 1;
            }

            double netWorth = ctx.netWorthMinorUnits() / 100.0;
            double bank = ctx.bankBalanceMinorUnits() / 100.0;

            String htmlTooltip = String.format(
                    "<div class=\"skyblock-tooltip\" style=\"padding:5px;font-family:sans-serif;\">"
                            + "<h3 style=\"margin:0 0 5px 0;\">%s</h3>"
                            + "<div><b>Owner:</b> %s</div>"
                            + "<div><b>Level:</b> %,d</div>"
                            + "<div><b>Rank:</b> %s</div>"
                            + "<div><b>Net Worth:</b> $%,.2f</div>"
                            + "<div><b>Bank:</b> $%,.2f</div>"
                            + "</div>",
                    escapeHtml(displayName),
                    island.ownerPlayerUuid().value().toString().substring(0, 8),
                    ctx.levelScore(),
                    ctx.rank() > 0 ? "#" + ctx.rank() : "Unranked",
                    netWorth,
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

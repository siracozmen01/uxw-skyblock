package com.uxplima.uxmskyblock.core.application.webmap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.webmap.IslandWebMapService.IslandMapContext;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.webmap.WebMapMarker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandWebMapServiceTest {

    private IslandWebMapService webMapService;
    private Instant now;

    @BeforeEach
    void setUp() {
        webMapService = new IslandWebMapService();
        now = Instant.parse("2026-09-19T12:00:00Z");
    }

    private Island createTestIsland(int centerX, int centerZ) {
        return Island.create(
                new IslandId(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(centerX, centerZ, 50),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                now);
    }

    @Test
    @DisplayName("Top 10 ranked islands receive gold boundary highlights and styling")
    void top10GoldStyling() {
        Island island = createTestIsland(0, 0);
        IslandMapContext ctx = new IslandMapContext(
                island,
                "GoldCitadel",
                125000L,
                50000000L, // $500,000.00
                2500000L, // $25,000.00
                1, // rank #1
                false);

        List<WebMapMarker> markers = webMapService.generateMarkers(List.of(ctx));

        assertThat(markers).hasSize(1);
        WebMapMarker marker = markers.get(0);
        assertThat(marker.borderColorHex()).isEqualTo(IslandWebMapService.COLOR_GOLD_BORDER);
        assertThat(marker.fillColorHex()).isEqualTo(IslandWebMapService.COLOR_GOLD_FILL);
        assertThat(marker.lineWeight()).isEqualTo(3);
        assertThat(marker.htmlTooltip()).contains("GoldCitadel");
        assertThat(marker.htmlTooltip()).contains("#1");
        assertThat(marker.htmlTooltip()).contains("$500,000.00");
    }

    @Test
    @DisplayName("Allied islands receive alliance green highlights")
    void alliedGreenStyling() {
        Island island = createTestIsland(500, 500);
        IslandMapContext ctx = new IslandMapContext(
                island,
                "AlliedOutpost",
                50000L,
                1000000L,
                50000L,
                15, // rank #15 (not top 10)
                true); // allied

        List<WebMapMarker> markers = webMapService.generateMarkers(List.of(ctx));

        assertThat(markers).hasSize(1);
        WebMapMarker marker = markers.get(0);
        assertThat(marker.borderColorHex()).isEqualTo(IslandWebMapService.COLOR_ALLIANCE_BORDER);
        assertThat(marker.fillColorHex()).isEqualTo(IslandWebMapService.COLOR_ALLIANCE_FILL);
        assertThat(marker.lineWeight()).isEqualTo(2);
    }

    @Test
    @DisplayName("Standard unranked islands receive default blue styling")
    void standardBlueStyling() {
        Island island = createTestIsland(1000, 1000);
        IslandMapContext ctx = new IslandMapContext(
                island, null, 100L, 5000L, 0L, -1, // unranked
                false);

        List<WebMapMarker> markers = webMapService.generateMarkers(List.of(ctx));

        assertThat(markers).hasSize(1);
        WebMapMarker marker = markers.get(0);
        assertThat(marker.borderColorHex()).isEqualTo(IslandWebMapService.COLOR_DEFAULT_BORDER);
        assertThat(marker.displayName()).startsWith("Island ");
        assertThat(marker.htmlTooltip()).contains("Unranked");
    }
}

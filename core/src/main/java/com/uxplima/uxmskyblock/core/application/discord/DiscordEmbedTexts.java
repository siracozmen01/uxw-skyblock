package com.uxplima.uxmskyblock.core.application.discord;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What each Discord embed says, as the operator wrote it.
 *
 * <p>Every title, description, field name, footer and colour used to be an English sentence in the
 * service. A server whose community speaks Turkish, or that names itself something other than the
 * product, had no way to change a word of it. Each text is a template: a {@code <name>} in it is
 * replaced with the value the embed carries, and a name the embed does not carry stays as written.
 */
public record DiscordEmbedTexts(Milestone milestone, Leaderboard leaderboard, Alliance alliance, Audit audit) {

    public DiscordEmbedTexts {
        Objects.requireNonNull(milestone, "milestone must not be null");
        Objects.requireNonNull(leaderboard, "leaderboard must not be null");
        Objects.requireNonNull(alliance, "alliance must not be null");
        Objects.requireNonNull(audit, "audit must not be null");
    }

    /** An island reaching a level. Carries {@code <island>}, {@code <leader>}, {@code <level>} and {@code <count>}. */
    public record Milestone(
            String title,
            String description,
            String leaderField,
            String levelField,
            String membersField,
            String footer,
            int color) {}

    /**
     * The top islands. The title carries {@code <metric>}; each line carries {@code <place>},
     * {@code <island>} and {@code <score>}, and {@code <place>} is a medal for the first three and
     * {@code otherPlace} for the rest, which carries {@code <rank>}.
     */
    public record Leaderboard(
            String title,
            String line,
            String firstPlace,
            String secondPlace,
            String thirdPlace,
            String otherPlace,
            String empty,
            String footer,
            int color) {}

    /** A diplomatic event. Carries {@code <alliance>}, {@code <action>}, {@code <actor>} and {@code <target>}. */
    public record Alliance(
            String title,
            String description,
            String allianceField,
            String actorField,
            String targetField,
            String footer,
            int color) {}

    /**
     * A staff audit alert. The title carries {@code <severity>} and {@code <event>}. The colour
     * follows the severity: critical or high, medium or warn, and everything else.
     */
    public record Audit(String title, String footer, int highColor, int mediumColor, int lowColor) {}

    /** The texts the plugin shipped with before they could be changed. */
    public static DiscordEmbedTexts english() {
        return new DiscordEmbedTexts(
                new Milestone(
                        "🌟 Island Level Milestone Reached!",
                        "Island **<island>** has achieved Level **<level>**!",
                        "Leader",
                        "Island Level",
                        "Members (<count>)",
                        "UXPLIMA Skyblock Milestones",
                        0xFFD700),
                new Leaderboard(
                        "🏆 Top Islands Leaderboard: <metric>",
                        "<place> **<island>**: <score>",
                        "🥇",
                        "🥈",
                        "🥉",
                        "`#<rank>`",
                        "No leaderboard entries recorded.",
                        "UXPLIMA Skyblock Leaderboard",
                        0xFFA500),
                new Alliance(
                        "⚔️ Diplomatic Event: <action>",
                        "Alliance update concerning **<alliance>**.",
                        "Alliance",
                        "Initiated By",
                        "Target",
                        "UXPLIMA Skyblock Diplomacy",
                        0x3498DB),
                new Audit(
                        "🛡️ Staff Audit Alert [<severity>]: <event>",
                        "UXPLIMA Skyblock Administrative Audit",
                        0xE74C3C,
                        0xE67E22,
                        0x95A5A6));
    }

    private static final Pattern NAME = Pattern.compile("<([a-z]+)>");

    /**
     * The template with every {@code <name>} it has a value for replaced.
     *
     * <p>One pass over the template, so a value is never read again. An island a player named
     * {@code <level>} is called that, rather than being replaced with the level.
     */
    public static String fill(String template, Map<String, String> values) {
        Matcher names = NAME.matcher(template);
        StringBuilder filled = new StringBuilder(template.length());
        while (names.find()) {
            String value = values.get(names.group(1));
            names.appendReplacement(filled, Matcher.quoteReplacement(value != null ? value : names.group()));
        }
        names.appendTail(filled);
        return filled.toString();
    }
}

package com.uxplima.uxmskyblock.core.domain.module;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Immutable Semantic Versioning 2.0.0 implementation supporting version comparison
 * and range expression matching (caret, tilde, comparisons, wildcards).
 */
public final class SemVer implements Comparable<SemVer> {

    private static final Pattern SEMVER_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?$");

    private final int major;
    private final int minor;
    private final int patch;
    private final @Nullable String prerelease;

    public SemVer(int major, int minor, int patch, @Nullable String prerelease) {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException(
                    "Version numbers cannot be negative: " + major + "." + minor + "." + patch);
        }
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease != null && !prerelease.isBlank() ? prerelease : null;
    }

    public static SemVer of(int major, int minor, int patch) {
        return new SemVer(major, minor, patch, null);
    }

    public static SemVer parse(String versionString) {
        Objects.requireNonNull(versionString, "versionString cannot be null");
        String trimmed = versionString.trim();
        Matcher matcher = SEMVER_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid SemVer 2.0.0 string: '" + versionString + "'");
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        int patch = Integer.parseInt(matcher.group(3));
        String prerelease = matcher.group(4);
        return new SemVer(major, minor, patch, prerelease);
    }

    public static DependencyRequirement parseRequirement(String requirementString) {
        Objects.requireNonNull(requirementString, "requirementString cannot be null");
        String trimmed = requirementString.trim();
        int firstSpace = trimmed.indexOf(' ');
        if (firstSpace == -1) {
            return new DependencyRequirement(trimmed, "*");
        }
        String id = trimmed.substring(0, firstSpace).trim();
        String range = trimmed.substring(firstSpace + 1).trim();
        return new DependencyRequirement(id, range.isEmpty() ? "*" : range);
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

    public Optional<String> prerelease() {
        return Optional.ofNullable(prerelease);
    }

    private static java.util.List<String> splitByWhitespace(String input) {
        java.util.List<String> list = new java.util.ArrayList<>();
        java.util.StringTokenizer tokenizer = new java.util.StringTokenizer(input);
        while (tokenizer.hasMoreTokens()) {
            list.add(tokenizer.nextToken());
        }
        return list;
    }

    private static java.util.List<String> splitByDot(String input) {
        java.util.List<String> list = new java.util.ArrayList<>();
        int start = 0;
        int idx;
        while ((idx = input.indexOf('.', start)) != -1) {
            list.add(input.substring(start, idx));
            start = idx + 1;
        }
        list.add(input.substring(start));
        return list;
    }

    private static String normalizeRange(String input) {
        String s = input;
        s = s.replace(">= ", ">=");
        s = s.replace("<= ", "<=");
        s = s.replace("> ", ">");
        s = s.replace("< ", "<");
        s = s.replace("= ", "=");
        s = s.replace("^ ", "^");
        s = s.replace("~ ", "~");
        return s;
    }

    public boolean satisfies(String rangeExpression) {
        Objects.requireNonNull(rangeExpression, "rangeExpression cannot be null");
        String trimmed = rangeExpression.trim();
        if (trimmed.isEmpty() || "*".equals(trimmed)) {
            return true;
        }

        String normalized = normalizeRange(trimmed);

        // Handle space-separated compound clauses, e.g. ">=1.2.0 <2.0.0"
        if (normalized.contains(" ")) {
            java.util.List<String> parts = splitByWhitespace(normalized);
            for (String part : parts) {
                if (!satisfiesSingleClause(part)) {
                    return false;
                }
            }
            return true;
        }

        return satisfiesSingleClause(normalized);
    }

    private boolean satisfiesSingleClause(String clause) {
        String trimmed = clause.trim();
        if (trimmed.isEmpty() || "*".equals(trimmed)) {
            return true;
        }

        if (trimmed.startsWith("^")) {
            SemVer base = parse(trimmed.substring(1).trim());
            if (this.compareTo(base) < 0) {
                return false;
            }
            if (base.major > 0) {
                return this.major == base.major;
            } else if (base.minor > 0) {
                return this.major == 0 && this.minor == base.minor;
            } else {
                return this.compareTo(base) == 0;
            }
        }

        if (trimmed.startsWith("~")) {
            SemVer base = parse(trimmed.substring(1).trim());
            if (this.compareTo(base) < 0) {
                return false;
            }
            return this.major == base.major && this.minor == base.minor;
        }

        if (trimmed.endsWith(".*")) {
            String prefix = trimmed.substring(0, trimmed.length() - 2).trim();
            java.util.List<String> segments = splitByDot(prefix);
            if (segments.size() == 1) {
                int reqMajor = Integer.parseInt(segments.get(0));
                return this.major == reqMajor;
            } else if (segments.size() == 2) {
                int reqMajor = Integer.parseInt(segments.get(0));
                int reqMinor = Integer.parseInt(segments.get(1));
                return this.major == reqMajor && this.minor == reqMinor;
            }
        }

        if (trimmed.startsWith(">=")) {
            SemVer target = parse(trimmed.substring(2).trim());
            return this.compareTo(target) >= 0;
        }

        if (trimmed.startsWith("<=")) {
            SemVer target = parse(trimmed.substring(2).trim());
            return this.compareTo(target) <= 0;
        }

        if (trimmed.startsWith(">")) {
            SemVer target = parse(trimmed.substring(1).trim());
            return this.compareTo(target) > 0;
        }

        if (trimmed.startsWith("<")) {
            SemVer target = parse(trimmed.substring(1).trim());
            return this.compareTo(target) < 0;
        }

        if (trimmed.startsWith("=")) {
            SemVer target = parse(trimmed.substring(1).trim());
            return this.compareTo(target) == 0;
        }

        SemVer target = parse(trimmed);
        return this.compareTo(target) == 0;
    }

    @Override
    public int compareTo(SemVer other) {
        if (this.major != other.major) {
            return Integer.compare(this.major, other.major);
        }
        if (this.minor != other.minor) {
            return Integer.compare(this.minor, other.minor);
        }
        if (this.patch != other.patch) {
            return Integer.compare(this.patch, other.patch);
        }
        // Release has higher precedence than prerelease
        if (this.prerelease == null && other.prerelease != null) {
            return 1;
        }
        if (this.prerelease != null && other.prerelease == null) {
            return -1;
        }
        if (this.prerelease != null) {
            return this.prerelease.compareTo(other.prerelease);
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SemVer semVer)) return false;
        return major == semVer.major
                && minor == semVer.minor
                && patch == semVer.patch
                && Objects.equals(prerelease, semVer.prerelease);
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, prerelease);
    }

    @Override
    public String toString() {
        if (prerelease != null) {
            return major + "." + minor + "." + patch + "-" + prerelease;
        }
        return major + "." + minor + "." + patch;
    }
}

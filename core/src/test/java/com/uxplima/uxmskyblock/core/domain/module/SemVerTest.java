package com.uxplima.uxmskyblock.core.domain.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SemVerTest {

    @Test
    @DisplayName("parses standard SemVer 2.0.0 components accurately")
    void parsesStandardSemVerComponents() {
        SemVer v = SemVer.parse("1.2.3");
        assertThat(v.major()).isEqualTo(1);
        assertThat(v.minor()).isEqualTo(2);
        assertThat(v.patch()).isEqualTo(3);
        assertThat(v.prerelease()).isEmpty();
        assertThat(v.toString()).isEqualTo("1.2.3");
    }

    @Test
    @DisplayName("parses SemVer with prerelease identifier")
    void parsesSemVerWithPrerelease() {
        SemVer v = SemVer.parse("2.0.0-rc1");
        assertThat(v.major()).isEqualTo(2);
        assertThat(v.minor()).isEqualTo(0);
        assertThat(v.patch()).isEqualTo(0);
        assertThat(v.prerelease()).contains("rc1");
    }

    @Test
    @DisplayName("compares versions correctly based on precedence")
    void comparesVersionsCorrectly() {
        SemVer v100 = SemVer.parse("1.0.0");
        SemVer v120 = SemVer.parse("1.2.0");
        SemVer v123 = SemVer.parse("1.2.3");
        SemVer v200 = SemVer.parse("2.0.0");

        assertThat(v100).isLessThan(v120);
        assertThat(v120).isLessThan(v123);
        assertThat(v123).isLessThan(v200);
        assertThat(v123).isEqualByComparingTo(SemVer.parse("1.2.3"));
    }

    @Test
    @DisplayName("matches exact version expressions")
    void matchesExactVersionExpressions() {
        SemVer v = SemVer.parse("1.2.3");
        assertThat(v.satisfies("1.2.3")).isTrue();
        assertThat(v.satisfies("1.2.4")).isFalse();
    }

    @Test
    @DisplayName("matches caret range expressions correctly")
    void matchesCaretRangeExpressions() {
        assertThat(SemVer.parse("1.2.0").satisfies("^1.2.0")).isTrue();
        assertThat(SemVer.parse("1.2.5").satisfies("^1.2.0")).isTrue();
        assertThat(SemVer.parse("1.9.9").satisfies("^1.2.0")).isTrue();
        assertThat(SemVer.parse("2.0.0").satisfies("^1.2.0")).isFalse();
        assertThat(SemVer.parse("1.1.9").satisfies("^1.2.0")).isFalse();
    }

    @Test
    @DisplayName("matches tilde range expressions correctly")
    void matchesTildeRangeExpressions() {
        assertThat(SemVer.parse("1.2.0").satisfies("~1.2.0")).isTrue();
        assertThat(SemVer.parse("1.2.9").satisfies("~1.2.0")).isTrue();
        assertThat(SemVer.parse("1.3.0").satisfies("~1.2.0")).isFalse();
        assertThat(SemVer.parse("1.1.9").satisfies("~1.2.0")).isFalse();
    }

    @Test
    @DisplayName("matches comparison range expressions")
    void matchesComparisonRangeExpressions() {
        assertThat(SemVer.parse("1.5.0").satisfies(">=1.2.0 <2.0.0")).isTrue();
        assertThat(SemVer.parse("1.2.0").satisfies(">=1.2.0 <2.0.0")).isTrue();
        assertThat(SemVer.parse("2.0.0").satisfies(">=1.2.0 <2.0.0")).isFalse();
        assertThat(SemVer.parse("1.1.0").satisfies(">=1.2.0 <2.0.0")).isFalse();
    }

    @Test
    @DisplayName("matches wildcard range expressions")
    void matchesWildcardExpressions() {
        assertThat(SemVer.parse("1.2.5").satisfies("1.2.*")).isTrue();
        assertThat(SemVer.parse("1.3.0").satisfies("1.2.*")).isFalse();
        assertThat(SemVer.parse("9.9.9").satisfies("*")).isTrue();
    }

    @Test
    @DisplayName("parses dependency requirement strings into id and version range")
    void parsesDependencyRequirements() {
        DependencyRequirement req1 = SemVer.parseRequirement("core >= 1.0.0");
        assertThat(req1.moduleId()).isEqualTo("core");
        assertThat(req1.rangeExpression()).isEqualTo(">= 1.0.0");

        DependencyRequirement req2 = SemVer.parseRequirement("missions ^1.0.0");
        assertThat(req2.moduleId()).isEqualTo("missions");
        assertThat(req2.rangeExpression()).isEqualTo("^1.0.0");

        DependencyRequirement req3 = SemVer.parseRequirement("island-bank");
        assertThat(req3.moduleId()).isEqualTo("island-bank");
        assertThat(req3.rangeExpression()).isEqualTo("*");
    }

    @Test
    @DisplayName("rejects malformed SemVer strings")
    void rejectsMalformedSemVer() {
        assertThatThrownBy(() -> SemVer.parse("invalid")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SemVer.parse("1.2")).isInstanceOf(IllegalArgumentException.class);
    }
}

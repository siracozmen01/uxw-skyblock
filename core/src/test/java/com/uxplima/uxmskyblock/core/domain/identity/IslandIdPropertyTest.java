package com.uxplima.uxmskyblock.core.domain.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class IslandIdPropertyTest {

    @Provide
    Arbitrary<IslandId> islandIds() {
        Arbitrary<Long> longs = Arbitraries.longs();
        return Combinators.combine(longs, longs).as((msb, lsb) -> IslandId.of(new UUID(msb, lsb)));
    }

    @Property
    @SuppressWarnings("SelfComparison")
    void reflexivity(@ForAll("islandIds") IslandId a) {
        IslandId copy = IslandId.of(
                new UUID(a.value().getMostSignificantBits(), a.value().getLeastSignificantBits()));
        assertThat(a.compareTo(copy)).isZero();
    }

    @Property
    void antiSymmetry(@ForAll("islandIds") IslandId a, @ForAll("islandIds") IslandId b) {
        int ab = Integer.signum(a.compareTo(b));
        int ba = Integer.signum(b.compareTo(a));
        assertThat(ab).isEqualTo(-ba);
    }

    @Property
    void transitivity(
            @ForAll("islandIds") IslandId a, @ForAll("islandIds") IslandId b, @ForAll("islandIds") IslandId c) {
        if (a.compareTo(b) > 0 && b.compareTo(c) > 0) {
            assertThat(a.compareTo(c)).isGreaterThan(0);
        } else if (a.compareTo(b) < 0 && b.compareTo(c) < 0) {
            assertThat(a.compareTo(c)).isLessThan(0);
        }
    }

    @Property
    void consistencyWithEquals(@ForAll("islandIds") IslandId a, @ForAll("islandIds") IslandId b) {
        boolean equals = a.equals(b);
        boolean compareZero = a.compareTo(b) == 0;
        assertThat(compareZero).isEqualTo(equals);
    }

    @Property
    void unsigned128BitOrderMatchesLongCompareUnsigned(
            @ForAll("islandIds") IslandId a, @ForAll("islandIds") IslandId b) {
        UUID u1 = a.value();
        UUID u2 = b.value();
        int expectedMsb = Long.compareUnsigned(u1.getMostSignificantBits(), u2.getMostSignificantBits());
        int expected = (expectedMsb != 0)
                ? expectedMsb
                : Long.compareUnsigned(u1.getLeastSignificantBits(), u2.getLeastSignificantBits());

        assertThat(Integer.signum(a.compareTo(b))).isEqualTo(Integer.signum(expected));
    }
}

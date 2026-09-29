package com.uxplima.uxmskyblock.core.domain.cave;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * What is carved out of a CaveBlock island's rock: the room its players arrive in, tunnels that wind
 * out of it and ravines cut deep through the rock.
 *
 * <p>The carving is a list of spheres, planned once from the island's seed. The rock is laid a chunk
 * at a time, each on the thread of the region that owns it, and every chunk asks the same plan which
 * of its blocks are carved, so a tunnel runs on unbroken from one chunk into the next. The same seed
 * always plans the same cave.
 */
public final class CavePlan {

    /** One carved sphere. */
    public record Carve(double x, double y, double z, double radius) {

        boolean contains(int blockX, int blockY, int blockZ) {
            double dx = blockX + 0.5 - x;
            double dy = blockY + 0.5 - y;
            double dz = blockZ + 0.5 - z;
            return dx * dx + dy * dy + dz * dz <= radius * radius;
        }

        boolean touches(int minX, int maxX, int minZ, int maxZ) {
            return x + radius >= minX && x - radius <= maxX + 1 && z + radius >= minZ && z - radius <= maxZ + 1;
        }
    }

    /**
     * The shape of a cave.
     *
     * @param radius how far the rock reaches sideways from the island's centre
     * @param below how many blocks of rock lie below the block players arrive standing on
     * @param above how many blocks of rock stand above it
     * @param room the radius of the room players arrive in
     * @param tunnels how many tunnels wind out of the room
     * @param tunnelLength how many blocks each tunnel runs
     * @param ravines how many ravines are cut through the rock
     * @param ravineLength how many blocks each ravine runs
     * @param ravineDepth how many blocks deep each ravine is cut
     */
    public record Shape(
            int radius,
            int below,
            int above,
            int room,
            int tunnels,
            int tunnelLength,
            int ravines,
            int ravineLength,
            int ravineDepth) {

        public static final Shape SHIPPED = new Shape(32, 24, 16, 4, 6, 40, 2, 30, 14);

        public Shape {
            if (radius < 8 || below < 4 || above < 4) {
                throw new IllegalArgumentException("the rock needs a radius of 8 and 4 blocks above and below");
            }
            if (room < 2 || room > Math.min(radius, above) - 2) {
                throw new IllegalArgumentException("the room is at least 2 and fits inside the rock");
            }
            if (tunnels < 0 || tunnelLength < 0 || ravines < 0 || ravineLength < 0 || ravineDepth < 0) {
                throw new IllegalArgumentException("tunnels and ravines are counted from 0");
            }
        }
    }

    private static final double TUNNEL_STEP = 1.0;

    private final List<Carve> carves;

    private CavePlan(List<Carve> carves) {
        this.carves = List.copyOf(carves);
    }

    /**
     * Plans the cave of an island whose players arrive standing on {@code (centerX, y, centerZ)}.
     *
     * <p>The room sits on that block, so the platform players stand on is its floor. Nothing is carved
     * within two blocks of the rock's edge, so the shell around it stays whole.
     */
    public static CavePlan plan(long seed, int centerX, int y, int centerZ, Shape shape) {
        Objects.requireNonNull(shape, "shape must not be null");
        SplittableRandom random = new SplittableRandom(seed);
        List<Carve> carves = new ArrayList<>();
        Bounds bounds = new Bounds(centerX, centerZ, shape.radius() - 2, y - shape.below() + 2, y + shape.above() - 2);
        carves.add(new Carve(centerX + 0.5, y + 1 + shape.room() / 2.0, centerZ + 0.5, shape.room()));
        for (int i = 0; i < shape.tunnels(); i++) {
            tunnel(random, carves, bounds, centerX + 0.5, y + 2.0, centerZ + 0.5, shape.tunnelLength());
        }
        for (int i = 0; i < shape.ravines(); i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = random.nextDouble() * shape.radius() / 2.0;
            ravine(
                    random,
                    carves,
                    bounds,
                    centerX + 0.5 + Math.cos(angle) * distance,
                    centerZ + 0.5 + Math.sin(angle) * distance,
                    y,
                    shape);
        }
        return new CavePlan(carves);
    }

    private static void tunnel(
            SplittableRandom random, List<Carve> carves, Bounds bounds, double x, double y, double z, int length) {
        double yaw = random.nextDouble() * Math.PI * 2;
        double pitch = (random.nextDouble() - 0.5) * 0.6;
        for (int step = 0; step < length; step++) {
            yaw += (random.nextDouble() - 0.5) * 0.5;
            pitch = Math.clamp(pitch + (random.nextDouble() - 0.5) * 0.3, -0.7, 0.7);
            double nextX = x + Math.cos(yaw) * Math.cos(pitch) * TUNNEL_STEP;
            double nextY = y + Math.sin(pitch) * TUNNEL_STEP;
            double nextZ = z + Math.sin(yaw) * Math.cos(pitch) * TUNNEL_STEP;
            if (!bounds.holds(nextX, nextY, nextZ, 2)) {
                // Turned back at the shell, never through it.
                yaw += Math.PI;
                pitch = -pitch;
                continue;
            }
            x = nextX;
            y = nextY;
            z = nextZ;
            carves.add(new Carve(x, y, z, 1.5 + random.nextDouble()));
        }
    }

    private static void ravine(
            SplittableRandom random, List<Carve> carves, Bounds bounds, double x, double z, int y, Shape shape) {
        double yaw = random.nextDouble() * Math.PI * 2;
        double bottom = Math.max(bounds.bottom() + 1.5, y - shape.ravineDepth() / 2.0 - shape.below() / 3.0);
        double top = Math.min(bounds.top() - 1.5, bottom + shape.ravineDepth());
        for (int step = 0; step < shape.ravineLength(); step++) {
            yaw += (random.nextDouble() - 0.5) * 0.2;
            double nextX = x + Math.cos(yaw);
            double nextZ = z + Math.sin(yaw);
            if (!bounds.holdsSideways(nextX, nextZ, 2)) {
                yaw += Math.PI;
                continue;
            }
            x = nextX;
            z = nextZ;
            for (double level = bottom; level <= top; level += 1.5) {
                carves.add(new Carve(x, level, z, 1.5));
            }
        }
    }

    /** Every sphere the plan carves. */
    public List<Carve> carves() {
        return carves;
    }

    /** The part of the plan that reaches into the columns from {@code minX..maxX} and {@code minZ..maxZ}. */
    public CavePlan within(int minX, int maxX, int minZ, int maxZ) {
        List<Carve> touching = new ArrayList<>();
        for (Carve carve : carves) {
            if (carve.touches(minX, maxX, minZ, maxZ)) {
                touching.add(carve);
            }
        }
        return new CavePlan(touching);
    }

    /** Whether the block is carved out of the rock. */
    public boolean carved(int x, int y, int z) {
        for (Carve carve : carves) {
            if (carve.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /** The rock's inside, where carving may reach. */
    private record Bounds(int centerX, int centerZ, int radius, int bottom, int top) {

        boolean holds(double x, double y, double z, double margin) {
            return holdsSideways(x, z, margin) && y >= bottom + margin && y <= top - margin;
        }

        boolean holdsSideways(double x, double z, double margin) {
            return Math.abs(x - centerX - 0.5) <= radius - margin && Math.abs(z - centerZ - 0.5) <= radius - margin;
        }
    }
}

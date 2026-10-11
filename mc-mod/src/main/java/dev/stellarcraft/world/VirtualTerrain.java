package dev.stellarcraft.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stellar Blade's ground, approximated as a height-field so Minecraft's own raycast can
 * "hit" it.
 *
 * <p>Stellar Blade's world is not Minecraft blocks, so Minecraft's block raycast finds
 * nothing to aim at and right-click never places a block. This class holds a coarse grid of
 * surface heights sampled on the SB side (a downward line trace per cell) and provides a
 * {@link #raycast} that returns a {@link BlockHitResult} when the player's view ray crosses
 * one of those surfaces. A mixin feeds that result into {@code Minecraft.hitResult}, after
 * which Minecraft's normal {@code useItemOn} path places the block for real.
 *
 * <p>Cells are keyed by integer grid coords; each stores the surface Y in <em>Minecraft</em>
 * coordinates. The grid is intentionally coarse (0.5 blocks) and evicted by distance,
 * mirroring how SekiroCraft's native sampler works.
 */
public final class VirtualTerrain {
    /** Grid cell size in blocks (SB sampler uses 0.5 m). */
    public static final double CELL = 0.5;

    /** An immutable snapshot entry: grid coords + surface top Y (MC coords). */
    private record Entry(int gx, int gz, double topY) {}

    private static final Map<Long, Entry> cells = new HashMap<>();
    /** Volatile copy for lock-free raycasts from the render thread. */
    private static volatile List<Entry> snapshot = List.of();

    private VirtualTerrain() {}

    private static long key(int gx, int gz) {
        return (((long) gx) << 32) ^ (gz & 0xFFFFFFFFL);
    }

    /** Sets one cell's surface height (MC Y of the ground top). */
    public static synchronized void put(int gx, int gz, double topY) {
        cells.put(key(gx, gz), new Entry(gx, gz, topY));
        snapshot = new ArrayList<>(cells.values());
    }

    /** Marks a cell as known-empty (a confirmed ray miss means open air). */
    public static synchronized void remove(int gx, int gz) {
        if (cells.remove(key(gx, gz)) != null) {
            snapshot = new ArrayList<>(cells.values());
        }
    }

    /** Keeps only cells within {@code radius} grid steps of (px,pz). */
    public static synchronized void prune(double px, double pz, int radius) {
        int cx = (int) Math.floor(px / CELL);
        int cz = (int) Math.floor(pz / CELL);
        boolean changed = false;
        var it = cells.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (Math.abs(e.gx() - cx) > radius || Math.abs(e.gz() - cz) > radius) {
                it.remove();
                changed = true;
            }
        }
        if (changed) snapshot = new ArrayList<>(cells.values());
    }

    public static synchronized void clear() {
        cells.clear();
        snapshot = List.of();
    }

    public static boolean isEmpty() {
        return snapshot.isEmpty();
    }
    public static int count() {return snapshot.size();}

    /**
     * Casts the player's view ray against the sampled ground. Returns a hit only when the
     * ray is descending and crosses a cell's surface inside that cell's XZ footprint.
     *
     * <p>Preserves the actual surface Y. The native build ledger stores its fractional
     * offset separately from the integer Minecraft block address.
     */
    public static BlockHitResult raycast(Vec3 start, Vec3 end) {
        double dy = end.y - start.y;
        if (dy >= -1e-7) return null; // not descending -> cannot hit a ground top

        double nearest = Double.POSITIVE_INFINITY;
        Vec3 hit = null;

        for (Entry e : snapshot) {
            double t = (e.topY() - start.y) / dy;
            if (t < 0 || t > 1 || t >= nearest) continue;

            double x = start.x + (end.x - start.x) * t;
            double z = start.z + (end.z - start.z) * t;
            double minX = e.gx() * CELL, maxX = minX + CELL;
            double minZ = e.gz() * CELL, maxZ = minZ + CELL;
            if (x < minX || x >= maxX || z < minZ || z >= maxZ) continue;

            // Keep the trace height: rounding it to a whole MC cell floats geometry.
            nearest = t;
            hit = new Vec3(x, e.topY(), z);
        }

        return hit == null ? null : new BlockHitResult(hit, Direction.UP, BlockPos.containing(hit), false);
    }
}

package dev.stellarcraft.world;

import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A sparse store of axis-aligned collision boxes representing Stellar Blade's world
 * geometry, injected into Minecraft's collision queries.
 *
 * <p>This is the heart of the "walk on the other game's terrain" mechanic. Stellar Blade
 * (the UE4SS mod) streams AABBs here via the collision ring; a Mixin appends these shapes
 * to every {@code VoxelShape} query Minecraft's movement code makes, so Minecraft's own
 * collision resolution (gravity, step-up, sprint-jump, sneak-at-edge) runs <em>unchanged</em>
 * against foreign geometry.
 *
 * <p>Shapes are bucketed per 16³ section so lookups stay O(1)-ish and far sections can be
 * evicted. Resolution is at whatever granularity SB sends (the MVP uses ray-cast on a grid,
 * typically 1/8-block micro-steps for slopes).
 */
public final class CollisionField {
    /** Max sections to keep resident. Evicts farthest beyond this. */
    private static final int MAX_SECTIONS = 256;

    private final Map<Long, Section> sections = new HashMap<>();

    private static final class Section {
        final List<AABB> boxes = new ArrayList<>();
    }

    /** Replaces a section's boxes (sent as a streamed delta). */
    public void putSection(int sx, int sy, int sz, List<AABB> boxes) {
        long key = key(sx, sy, sz);
        Section s = sections.get(key);
        if (s == null) {
            s = new Section();
            sections.put(key, s);
        }
        s.boxes.clear();
        s.boxes.addAll(boxes);
        evictIfNeeded();
    }

    /** Removes a section entirely (e.g. unloaded geometry). */
    public void evictSection(int sx, int sy, int sz) {
        sections.remove(key(sx, sy, sz));
    }

    /** Clears everything (level change). */
    public void clear() {
        sections.clear();
    }

    /**
     * Returns all collision boxes overlapping the given query box.
     * A Mixin appends these to the movement collision set.
     */
    public void forEachOverlapping(AABB query, List<AABB> out) {
        int minX = SectionPos.blockToSectionCoord(Math.floor(query.minX));
        int minY = SectionPos.blockToSectionCoord(Math.floor(query.minY));
        int minZ = SectionPos.blockToSectionCoord(Math.floor(query.minZ));
        int maxX = SectionPos.blockToSectionCoord(Math.floor(query.maxX));
        int maxY = SectionPos.blockToSectionCoord(Math.floor(query.maxY));
        int maxZ = SectionPos.blockToSectionCoord(Math.floor(query.maxZ));

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Section s = sections.get(key(x, y, z));
                    if (s == null) continue;
                    for (AABB box : s.boxes) {
                        if (box.intersects(query)) out.add(box);
                    }
                }
            }
        }
    }

    public boolean isEmpty() { return sections.isEmpty(); }

    private void evictIfNeeded() {
        if (sections.size() <= MAX_SECTIONS) return;
        // Simple policy: drop an arbitrary section. A real implementation tracks last-use
        // time and evicts the farthest/oldest. Kept simple for the skeleton.
        sections.keySet().iterator().next();
        sections.remove(sections.keySet().iterator().next());
    }

    private static long key(int sx, int sy, int sz) {
        return ((long) (sx & 0x3FFFFF) << 42)
             | ((long) (sy & 0x3FFFFF) << 21)
             |  (long) (sz & 0x3FFFFF);
    }
}

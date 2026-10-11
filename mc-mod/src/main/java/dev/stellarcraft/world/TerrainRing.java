package dev.stellarcraft.world;

import dev.stellarcraft.link.Proto;
import dev.stellarcraft.link.RingBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Drains the collision ring's {@code kColGround} messages into {@link VirtualTerrain}.
 *
 * <p>Stellar Blade samples its own ground with downward line traces and streams the result
 * as a height field. We consume those patches here and hand them to the pick-raycast
 * override, so Minecraft's crosshair can land on SB's ground and let the player place blocks.
 *
 * <p>Message wire format (little-endian):
 * <pre>
 *   u32 op (== 4)
 *   CollisionGroundHeader { i32 originGx, i32 originGz, u32 count, u32 pad }   // 16 bytes
 *   CollisionGroundCell[count] { i32 gx, i32 gz, f32 topY, u32 hit }          // 16 bytes each
 * </pre>
 */
public final class TerrainRing {
    private static final int OP_GROUND = 4;

    private static final int HDR_BYTES = 16;
    private static final int CELL_BYTES = 16;

    private static RingBuffer ring;
    private static final ByteBuffer scratch = ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN);
    private static int pruneCountdown = 0;

    private TerrainRing() {}

    public static void init(RingBuffer collisionRing) {
        ring = collisionRing;
    }

    /** Called once per client tick. */
    public static void tick(Minecraft mc) {
        if (ring == null) return;

        while (!ring.isEmpty()) {
            long cursor = ring.tail();
            if (ring.head() - cursor < 4) break;

            scratch.clear();
            ring.readBytes(cursor, 4, scratch);
            int op = scratch.getInt(0);

            if (op == OP_GROUND) {
                if (ring.head() - cursor < 4 + HDR_BYTES) break; // header not fully here yet
                scratch.clear();
                ring.readBytes(cursor + 4, HDR_BYTES, scratch);
                int count = scratch.getInt(8);
                if (count < 0 || count > 512) { // corrupt -> resync by dropping one byte
                    ring.setTail(cursor + 4);
                    continue;
                }
                int total = 4 + HDR_BYTES + count * CELL_BYTES;
                if (ring.head() - cursor < total) break; // wait for the whole message

                scratch.clear();
                ring.readBytes(cursor + 4 + HDR_BYTES, count * CELL_BYTES, scratch);
                for (int i = 0; i < count; i++) {
                    int base = i * CELL_BYTES;
                    int gx = scratch.getInt(base);
                    int gz = scratch.getInt(base + 4);
                    float topY = scratch.getFloat(base + 8);
                    int hit = scratch.getInt(base + 12);
                    if (hit != 0) {
                        VirtualTerrain.put(gx, gz, topY);
                    } else {
                        VirtualTerrain.remove(gx, gz);
                    }
                }
                ring.setTail(cursor + total);
            } else {
                // Unknown / legacy op we do not handle here: skip the single op word so we
                // do not stall the ring. (The AABB path is not consumed anywhere yet.)
                ring.setTail(cursor + 4);
            }
        }

        // Periodically drop cells far from the player to bound memory.
        if (++pruneCountdown >= 40) {
            pruneCountdown = 0;
            LocalPlayer p = mc.player;
            if (p != null) VirtualTerrain.prune(p.getX(), p.getZ(), 40);
        }
    }

    /** Level change: forget all sampled ground. */
    public static void clear() {
        VirtualTerrain.clear();
    }

    static {
        // Keep the constant referenced so a future protocol change is caught at build time.
        if (Proto.COLLISION_DATA_BYTES <= 0) throw new IllegalStateException("collision ring empty");
    }
}

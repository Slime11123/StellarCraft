package dev.stellarcraft.link;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Opens the StellarCraft shared-memory mapping and exposes typed read/write access.
 *
 * <p>The mapping is backed by native code (see {@code src/main/cpp/bridge.cpp}) via JNI.
 * It exposes:
 * <ul>
 *   <li>the header (handshake + heartbeats)</li>
 *   <li>{@link #sbState()} — SB -> MC state, seqlock-protected</li>
 *   <li>{@link #mcState()} — MC -> SB state, seqlock-protected</li>
 *   <li>{@link #actorTable()} — SB -> MC actor proxy table</li>
 *   <li>{@link #inputRing()} / {@link #eventRing()} / {@link #collisionRing()} — SPSC rings</li>
 *   <li>{@link #overlay()} — triple-buffered overlay pixels</li>
 * </ul>
 *
 * <p>All buffers are little-endian (native order on x86-64 is already LE).
 */
public final class SharedMemory {
    private final ByteBuffer mapping;

    private final ByteBuffer header;
    private final ByteBuffer sbState;
    private final ByteBuffer mcState;
    private final ByteBuffer actorTable;
    private final ByteBuffer inputRing;
    private final ByteBuffer eventRing;
    private final ByteBuffer collisionRing;
    private final ByteBuffer overlay;
    private final ByteBuffer appearance;

    public SharedMemory(ByteBuffer mapping) {
        this.mapping = mapping;
        mapping.order(ByteOrder.LITTLE_ENDIAN);

        // Slice each region out of the single mapping.
        this.header       = slice(Proto.OFF_HEADER, 0x100);
        this.sbState      = slice(Proto.OFF_SB_STATE, Proto.SB_STATE_SIZE);
        this.mcState      = slice(Proto.OFF_MC_STATE, Proto.MC_STATE_SIZE);
        this.actorTable   = slice(Proto.OFF_ACTOR_TABLE, Proto.ACTOR_TABLE_SIZE);
        this.inputRing    = slice(Proto.OFF_INPUT_RING, 0x10000);
        this.eventRing    = slice(Proto.OFF_EVENT_RING, 0x10000);
        this.collisionRing = slice(Proto.OFF_COLLISION_RING, 0x10000);
        this.overlay      = slice(Proto.OFF_OVERLAY_PIXELS, Proto.OVERLAY_SLOT_BYTES * Proto.OVERLAY_SLOTS);
        this.appearance   = slice(Proto.OFF_APPEARANCE, (int) Proto.APPEARANCE_BYTES);
    }

    private ByteBuffer slice(long offset, int size) {
        ByteBuffer b = mapping.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        b.position((int) offset);
        b.limit((int) offset + size);
        return b.slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    // ---- Header ----------------------------------------------------------------------

    public int magic()        { return header.getInt(Proto.HDR_MAGIC); }
    public int version()      { return header.getInt(Proto.HDR_VERSION); }
    public int sbPid()        { return header.getInt(Proto.HDR_SB_PID); }
    public int mcPid()        { return header.getInt(Proto.HDR_MC_PID); }
    public long sbHeartbeat() { return header.getLong(Proto.HDR_SB_HB); }
    public long mcHeartbeat() { return header.getLong(Proto.HDR_MC_HB); }

    public void setMcPid(int pid)       { header.putInt(Proto.HDR_MC_PID, pid); }
    public void setMcHeartbeat(long ms) { header.putLong(Proto.HDR_MC_HB, ms); }

    /** Returns true once the SB side has written the correct magic + version. */
    public boolean isSbReady() {
        return magic() == Proto.K_MAGIC && version() == Proto.K_VERSION;
    }

    // ---- Seqlock reads ----------------------------------------------------------------

    /**
     * Reads an SB state snapshot. Returns {@code null} if the seqlock was mid-write
     * (the caller should retry next frame).
     */
    public SbStateSnapshot readSbState() {
        int seq = sbState.getInt(Proto.SB_SEQ);
        if ((seq & 1) == 1) return null; // odd = writing

        SbStateSnapshot s = new SbStateSnapshot();
        s.flags      = sbState.getInt(Proto.SB_FLAGS);
        s.levelId    = sbState.getInt(Proto.SB_LEVEL_ID);
        s.teleportSeq = sbState.getInt(Proto.SB_TELEPORT);
        s.posX       = sbState.getDouble(Proto.SB_POS_X);
        s.posY       = sbState.getDouble(Proto.SB_POS_Y);
        s.posZ       = sbState.getDouble(Proto.SB_POS_Z);
        s.yaw        = sbState.getFloat(Proto.SB_YAW);
        s.pitch      = sbState.getFloat(Proto.SB_PITCH);
        s.viewW      = sbState.getInt(Proto.SB_VIEW_W);
        s.viewH      = sbState.getInt(Proto.SB_VIEW_H);
        s.gameHour   = sbState.getFloat(Proto.SB_GAME_HOUR);

        return sbState.getInt(Proto.SB_SEQ) == seq ? s : null; // torn read
    }

    public void writeMcState(McStateSnapshot s) {
        int seq = mcState.getInt(Proto.MC_SEQ) + 1;
        mcState.putInt(Proto.MC_SEQ, seq | 1); // begin write (odd)
        mcState.putInt(Proto.MC_FLAGS, s.flags);
        mcState.putDouble(Proto.MC_X, s.x);
        mcState.putDouble(Proto.MC_Y, s.y);
        mcState.putDouble(Proto.MC_Z, s.z);
        mcState.putFloat(Proto.MC_YAW, s.yaw);
        mcState.putFloat(Proto.MC_PITCH, s.pitch);
        mcState.putFloat(Proto.MC_EYE_HEIGHT, s.eyeHeight);
        mcState.putInt(Proto.MC_TELEPORT_ACK, s.teleportAck);
        mcState.putInt(Proto.MC_GUI_SCALE, s.guiScale);
        mcState.putLong(Proto.MC_FRAME, s.frameCounter);
        mcState.putFloat(Proto.MC_FOV, s.fovDeg);
        mcState.putFloat(Proto.MC_BOB_PHASE, s.bobPhase);
        mcState.putFloat(Proto.MC_BOB_AMOUNT, s.bobAmount);
        mcState.putInt(Proto.MC_CAM_MODE, s.cameraMode);
        mcState.putFloat(Proto.MC_CAM_DIST, s.cameraDistance);
        mcState.putLong(Proto.MC_TICK_QPC, s.tickQpc);
        mcState.putDouble(Proto.MC_PREV_X, s.prevX);
        mcState.putDouble(Proto.MC_PREV_Y, s.prevY);
        mcState.putDouble(Proto.MC_PREV_Z, s.prevZ);
        mcState.putDouble(Proto.MC_CUR_X, s.curX);
        mcState.putDouble(Proto.MC_CUR_Y, s.curY);
        mcState.putDouble(Proto.MC_CUR_Z, s.curZ);
        mcState.putFloat(Proto.MC_TICK_EYE_O, s.tickEyeO);
        mcState.putFloat(Proto.MC_TICK_EYE, s.tickEye);
        mcState.putFloat(Proto.MC_WALKDIST_O, s.walkDistO);
        mcState.putFloat(Proto.MC_WALKDIST, s.walkDist);
        mcState.putFloat(Proto.MC_BOB_O, s.bobO);
        mcState.putFloat(Proto.MC_BOB, s.bob);
        mcState.putFloat(Proto.MC_TICK_MS, s.tickMs);
        mcState.putInt(Proto.MC_SEQ, seq + 1); // end write (even)
    }

    // ---- Actor table ------------------------------------------------------------------

    public int actorCount() {
        return actorTable.getInt(Proto.ACTOR_COUNT);
    }

    public ActorSnapshot readActor(int index) {
        int base = Proto.ACTOR_DATA + index * Proto.ACTOR_RECORD_SIZE;
        ActorSnapshot a = new ActorSnapshot();
        a.uid        = actorTable.getInt(base + Proto.AR_UID);
        a.flags      = actorTable.getInt(base + Proto.AR_FLAGS);
        a.x          = actorTable.getFloat(base + Proto.AR_X);
        a.y          = actorTable.getFloat(base + Proto.AR_Y);
        a.z          = actorTable.getFloat(base + Proto.AR_Z);
        a.yaw        = actorTable.getFloat(base + Proto.AR_YAW);
        a.width      = actorTable.getFloat(base + Proto.AR_WIDTH);
        a.height     = actorTable.getFloat(base + Proto.AR_HEIGHT);
        a.healthFrac = actorTable.getFloat(base + Proto.AR_HEALTH);
        a.level      = actorTable.getShort(base + Proto.AR_LEVEL);
        return a;
    }

    // ---- Rings (raw byte access; the ring code lives in RingBuffer) --------------------

    public ByteBuffer inputRing()    { return inputRing.duplicate().order(ByteOrder.LITTLE_ENDIAN); }
    public ByteBuffer eventRing()    { return eventRing.duplicate().order(ByteOrder.LITTLE_ENDIAN); }
    public ByteBuffer collisionRing() { return collisionRing.duplicate().order(ByteOrder.LITTLE_ENDIAN); }

    // ---- Overlay ------------------------------------------------------------------------

    public ByteBuffer overlaySlot(int slot) {
        ByteBuffer b = overlay.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        b.position(slot * (int) Proto.OVERLAY_SLOT_BYTES);
        return b.slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    public ByteBuffer mapping() { return mapping; }

    // ---- Appearance (MC -> SB skin/cape) ------------------------------------------------

    /** Writes the player's skin + cape into the appearance region (seqlock). */
    public void writeAppearance(int flags, int skinW, int skinH, int capeW, int capeH,
                                long skinHash, long capeHash,
                                ByteBuffer skinPixels, ByteBuffer capePixels) {
        int seq = appearance.getInt(Proto.APP_SEQ) + 1;
        appearance.putInt(Proto.APP_SEQ, seq | 1); // begin write (odd)

        appearance.putInt(Proto.APP_FLAGS, flags);
        appearance.putInt(Proto.APP_SKIN_W, skinW);
        appearance.putInt(Proto.APP_SKIN_H, skinH);
        appearance.putInt(Proto.APP_CAPE_W, capeW);
        appearance.putInt(Proto.APP_CAPE_H, capeH);
        appearance.putLong(Proto.APP_SKIN_HASH, skinHash);
        appearance.putLong(Proto.APP_CAPE_HASH, capeHash);

        // Commit header, then write pixels.
        appearance.putInt(Proto.APP_SEQ, seq + 1); // end write (even)

        if (skinPixels != null) {
            ByteBuffer dst = appearance.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            dst.position(Proto.APPEARANCE_HEADER_BYTES);
            dst.put(skinPixels);
        }
        if (capePixels != null) {
            ByteBuffer dst = appearance.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            dst.position((int) (Proto.APPEARANCE_HEADER_BYTES + Proto.SKIN_PIXELS_BYTES));
            dst.put(capePixels);
        }
    }

    /** Raw access to the appearance region for the SB side to read pixels. */
    public ByteBuffer appearanceRegion() {
        return appearance.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    }

    // ---- Snapshot types ----------------------------------------------------------------

    public static final class SbStateSnapshot {
        public int flags, levelId, teleportSeq, viewW, viewH;
        public double posX, posY, posZ;
        public float yaw, pitch, gameHour;
    }

    public static final class McStateSnapshot {
        public int flags;
        public double x, y, z;
        public float yaw, pitch, eyeHeight;
        public int teleportAck, guiScale;
        public long frameCounter;
        public float fovDeg, bobPhase, bobAmount;
        public int cameraMode;
        public float cameraDistance;
        public long tickQpc;
        public double prevX, prevY, prevZ, curX, curY, curZ;
        public float tickEyeO, tickEye, walkDistO, walkDist, bobO, bob, tickMs;
    }

    public static final class ActorSnapshot {
        public int uid, flags;
        public float x, y, z, yaw, width, height, healthFrac;
        public short level;
    }
}

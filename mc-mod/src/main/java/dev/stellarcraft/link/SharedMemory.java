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
    private static final java.lang.invoke.VarHandle INTS=java.lang.invoke.MethodHandles.byteBufferViewVarHandle(
        int[].class,ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer mapping;

    private final ByteBuffer header;
    private final ByteBuffer sbState;
    private final ByteBuffer playerHurt;
    private final ByteBuffer mcState;
    private final ByteBuffer actorTable;
    private final ByteBuffer combat;
    private final ByteBuffer inputRing;
    private final ByteBuffer eventRing;
    private final ByteBuffer collisionRing;
    private final ByteBuffer overlay;
    private final ByteBuffer overlayCtl;
    private final ByteBuffer appearance;
    private final ByteBuffer nativePoses;

    public SharedMemory(ByteBuffer mapping) {
        this.mapping = mapping;
        this.combat = slice(Proto.OFF_COMBAT,Proto.COMBAT_BYTES);
        mapping.order(ByteOrder.LITTLE_ENDIAN);

        // Slice each region out of the single mapping.
        this.header       = slice(Proto.OFF_HEADER, 0x100);
        this.sbState      = slice(Proto.OFF_SB_STATE, Proto.SB_STATE_SIZE);
        this.playerHurt   = slice(Proto.OFF_SB_STATE+0x80,24);
        this.mcState      = slice(Proto.OFF_MC_STATE, Proto.MC_STATE_SIZE);
        this.actorTable   = slice(Proto.OFF_ACTOR_TABLE, Proto.ACTOR_TABLE_SIZE);
        this.inputRing    = slice(Proto.OFF_INPUT_RING, 0x10000);
        this.eventRing    = slice(Proto.OFF_EVENT_RING, 0x10000);
        // The collision ring is 32 MiB, not 64 KiB — sizing it wrong truncated every
        // section after the first few kilobytes.
        this.collisionRing = slice(Proto.OFF_COLLISION_RING, (int) Proto.COLLISION_RING_BYTES);
        this.overlay      = slice(Proto.OFF_OVERLAY_PIXELS, (int) (Proto.OVERLAY_SLOT_BYTES * Proto.OVERLAY_SLOTS));
        this.overlayCtl   = slice(Proto.OFF_OVERLAY_CTL, (int) Proto.OVERLAY_CTL_BYTES);
        this.appearance   = slice(Proto.OFF_APPEARANCE, (int) Proto.APPEARANCE_BYTES);
        this.nativePoses = slice(Proto.OFF_RENDER_RING,Proto.NATIVE_POSE_SLOTS*Proto.NATIVE_POSE_STRIDE);
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
    public boolean claimMcPid(int pid) {
        int owner=(int)INTS.getVolatile(header,Proto.HDR_MC_PID);
        if(owner==pid) return true;
        if(owner!=0&&ProcessHandle.of(Integer.toUnsignedLong(owner)).map(ProcessHandle::isAlive).orElse(false)) return false;
        return INTS.compareAndSet(header,Proto.HDR_MC_PID,owner,pid);
    }
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
        java.lang.invoke.VarHandle.acquireFence();

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
        s.mapId      = sbState.getInt(Proto.SB_MAP_ID);
        s.motionTimeMs = sbState.getLong(Proto.SB_MOTION_TIME);
        s.motionFrame = sbState.getLong(Proto.SB_MOTION_FRAME);
        s.motionEpoch = sbState.getInt(Proto.SB_MOTION_EPOCH);
        s.contactFlags = sbState.getInt(Proto.SB_CONTACTS);
        s.velocityX = sbState.getDouble(Proto.SB_VX);
        s.velocityY = sbState.getDouble(Proto.SB_VY);
        s.velocityZ = sbState.getDouble(Proto.SB_VZ);

        java.lang.invoke.VarHandle.acquireFence();
        return sbState.getInt(Proto.SB_SEQ) == seq ? s : null; // torn read
    }

    public record PlayerHurt(int guid,double total,int attacker,int blocked) {}
    public PlayerHurt readPlayerHurt() {
        int seq=playerHurt.getInt(0);if((seq&1)!=0) return null;
        java.lang.invoke.VarHandle.acquireFence();
        var result=new PlayerHurt(playerHurt.getInt(4),playerHurt.getDouble(8),playerHurt.getInt(16),playerHurt.getInt(20));
        java.lang.invoke.VarHandle.acquireFence();
        return seq==playerHurt.getInt(0)?result:null;
    }

    public void writeMcState(McStateSnapshot s) {
        // A crashed writer may leave an odd sequence. Always derive a fresh EVEN
        // completion value; adding two to an odd seed kept every later frame unreadable.
        int seq = (mcState.getInt(Proto.MC_SEQ) + 2) & ~1;
        mcState.putInt(Proto.MC_SEQ, seq - 1); // begin write (odd)
        java.lang.invoke.VarHandle.fullFence();
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
        mcState.putLong(Proto.MC_MOTION_TIME, s.motionTimeMs);
        mcState.putInt(Proto.MC_MOTION_EPOCH, s.motionEpoch);
        mcState.putInt(Proto.MC_MOTION_MODE, s.motionMode);
        mcState.putDouble(Proto.MC_VX, s.velocityX);
        mcState.putDouble(Proto.MC_VY, s.velocityY);
        mcState.putDouble(Proto.MC_VZ, s.velocityZ);
        mcState.putInt(Proto.MC_WORLD_ID,dev.stellarcraft.world.PlacedBlocks.worldId());
        java.lang.invoke.VarHandle.releaseFence();
        mcState.putInt(Proto.MC_SEQ, seq); // end write (even)
    }
    /** Single MC render-thread producer; a reader validates ownership before and after copying. */
    public void writeNativePose(int slot,int world,int entity,java.util.UUID uuid,byte[] bytes) {
        if(slot<0||slot>=Proto.NATIVE_POSE_SLOTS||bytes.length>Proto.NATIVE_POSE_STRIDE-64)
            throw new IllegalArgumentException("native pose bounds");
        int at=slot*Proto.NATIVE_POSE_STRIDE,seq=(nativePoses.getInt(at)+2)&~1;
        nativePoses.putInt(at,seq-1);java.lang.invoke.VarHandle.fullFence();
        nativePoses.putInt(at+4,bytes.length);nativePoses.putInt(at+8,(int)ProcessHandle.current().pid());
        nativePoses.putInt(at+12,sbPid());nativePoses.putInt(at+16,world);nativePoses.putInt(at+20,entity);
        nativePoses.putLong(at+24,uuid.getMostSignificantBits());nativePoses.putLong(at+32,uuid.getLeastSignificantBits());
        nativePoses.putLong(at+40,dev.stellarcraft.NativeLink.tickCount());
        var payload=nativePoses.duplicate();payload.position(at+64);payload.put(bytes);
        java.lang.invoke.VarHandle.releaseFence();nativePoses.putInt(at,seq);
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
        a.guid=actorTable.getInt(base+40);a.nativeType=actorTable.getInt(base+44);
        a.world=actorTable.getInt(base+48);a.generation=actorTable.getInt(base+52);
        return a;
    }

    public java.util.List<ActorSnapshot> readActors(int world) {
        int seq=actorTable.getInt(0);if((seq&1)!=0) return null;
        java.lang.invoke.VarHandle.acquireFence();
        long time=actorTable.getLong(8),now=dev.stellarcraft.NativeLink.tickCount();
        if(actorTable.getInt(16)!=sbPid()||actorTable.getInt(20)!=world||time>now||now-time>500) return java.util.List.of();
        int count=actorCount();if(count<0||count>Proto.MAX_ACTORS) return null;
        var rows=new java.util.ArrayList<ActorSnapshot>();for(int i=0;i<count;i++) rows.add(readActor(i));
        java.lang.invoke.VarHandle.acquireFence();return seq==actorTable.getInt(0)?rows:null;
    }
    public record CombatHit(long id,long time,int player,int rawSource,int world,float damage,int sourceGuid,
                            boolean directional,double x,double y,double z) {}
    public long combatHead() {return combat.getLong(0);}
    public CombatHit readCombatHit(long id) {
        int at=0x80+(int)(id%Proto.COMBAT_SLOTS)*Proto.COMBAT_HIT_SIZE;
        int seq=combat.getInt(at);if((seq&1)!=0) return null;
        java.lang.invoke.VarHandle.acquireFence();
        long actual=combat.getLong(at+8),time=combat.getLong(at+16),now=dev.stellarcraft.NativeLink.tickCount();
        int player=combat.getInt(at+24),raw=combat.getInt(at+28),pid=combat.getInt(at+32),world=combat.getInt(at+36);
        float damage=combat.getFloat(at+40);int guid=combat.getInt(at+44);
        double x=combat.getDouble(at+48),y=combat.getDouble(at+56),z=combat.getDouble(at+64);
        boolean direction=(combat.getInt(at+4)&1)!=0;
        java.lang.invoke.VarHandle.acquireFence();
        if(seq!=combat.getInt(at)||actual!=id||pid!=sbPid()||time>now||now-time>500||player<=0
            ||!Float.isFinite(damage)||damage<=0||damage>20
            ||direction&&(guid<=0||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))) return null;
        return new CombatHit(id,time,player,raw,world,damage,guid,direction,x,y,z);
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

    /**
     * The camera pose a frame was RENDERED WITH.
     *
     * <p>Carried per frame so a consumer can later re-project the frame from this pose onto
     * the host's <em>current</em> camera, which hides the link's latency instead of showing
     * it as lag between the overlay and the host's world. That technique is what
     * universal-modder's minecraft-gta5-passthrough does — see
     * {@code docs/GTA5_PASSTHROUGH_ANALYSIS.md} §2.2. Nothing consumes it yet; it is carried
     * so the data is available (and its accuracy observable) before anything depends on it.
     *
     * <p>{@code roll} is always 0 on Minecraft 1.20.1 — {@code Camera} has no roll field
     * before 1.21. It is in the layout anyway so a later MC version needs no protocol change.
     *
     * @param vFovDeg  effective vertical FOV actually used to render this frame (not the
     *                 options slider value: sprint/bow/underwater modifiers already applied)
     * @param nearZ    near clip plane in blocks (MC hardcodes 0.05)
     * @param farZ     far clip plane in blocks ({@code GameRenderer.getDepthFar()})
     */
    public record FramePose(double x, double y, double z,
                            float yaw, float pitch, float roll,
                            float vFovDeg, float aspect,
                            float nearZ, float farZ, int cameraMode) {}

    private long overlayFrames = 0;

    /**
     * Publishes a single overlay frame (MC HUD/GUI pixels, RGBA8) into slot 0 and marks it
     * dirty. {@code pixels} must be {@code width * height * 4} bytes. {@code bottomUp} sets
     * the slot's "rows are bottom-up" flag.
     */
    public void writeOverlayFrame(int width, int height, boolean bottomUp, ByteBuffer pixels) {
        writeOverlayFrame(width, height, bottomUp, pixels, null);
    }

    /** As above, but also records the pose the frame was rendered with (see {@link FramePose}). */
    public void writeOverlayFrame(int width, int height, boolean bottomUp, ByteBuffer pixels,
                                  FramePose pose) {
        // The in-process DX12 consumer copies this slot while MC keeps rendering. Mark the
        // entire pixel/header publication so it can reject a frame torn by a concurrent copy.
        int sequence = overlayCtl.getInt(Proto.OV_CTL_PAD);
        sequence = (sequence + 2) & ~1;
        overlayCtl.putInt(Proto.OV_CTL_PAD, sequence - 1);
        java.lang.invoke.VarHandle.fullFence();
        int bytes = width * height * 4;
        ByteBuffer slot = overlaySlot(0);
        // ONE bulk copy, not a byte-at-a-time loop. The loop this replaces performed 2 * bytes
        // ByteBuffer.get()/put() pairs — 63 million calls per frame for a 4K render target —
        // and was a large part of why the export path ran at ~12 fps.
        ByteBuffer dst = slot.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int n = Math.min(bytes, dst.remaining());
        ByteBuffer src = pixels.duplicate();
        src.position(0);
        src.limit(n);
        dst.put(src);

        // Write the slot header. Offsets live in Proto so this file cannot drift from the
        // C++ protocol header.
        int hdr = Proto.OV_SLOT_HDR;
        int flags = bottomUp ? Proto.OV_FLAG_BOTTOM_UP : 0;
        if (pose != null) flags |= Proto.OV_FLAG_POSE_VALID;

        overlayCtl.putInt(hdr + Proto.OV_SLOT_W, width);
        overlayCtl.putInt(hdr + Proto.OV_SLOT_H, height);
        overlayCtl.putInt(hdr + Proto.OV_SLOT_FLAGS, flags);
        overlayCtl.putLong(hdr + Proto.OV_SLOT_FRAME_ID, ++overlayFrames);

        if (pose != null) {
            overlayCtl.putDouble(hdr + Proto.OV_SLOT_CAM_X, pose.x());
            overlayCtl.putDouble(hdr + Proto.OV_SLOT_CAM_Y, pose.y());
            overlayCtl.putDouble(hdr + Proto.OV_SLOT_CAM_Z, pose.z());
            overlayCtl.putFloat(hdr + Proto.OV_SLOT_CAM_YAW, pose.yaw());
            overlayCtl.putFloat(hdr + Proto.OV_SLOT_CAM_PITCH, pose.pitch());
            overlayCtl.putFloat(hdr + Proto.OV_SLOT_CAM_ROLL, pose.roll());
            overlayCtl.putFloat(hdr + Proto.OV_SLOT_VFOV_DEG, pose.vFovDeg());
            overlayCtl.putFloat(hdr + Proto.OV_SLOT_ASPECT, pose.aspect());
            overlayCtl.putFloat(hdr + Proto.OV_SLOT_NEAR_Z, pose.nearZ());
            overlayCtl.putFloat(hdr + Proto.OV_SLOT_FAR_Z, pose.farZ());
            overlayCtl.putInt(hdr + Proto.OV_SLOT_CAMERA_MODE, pose.cameraMode());
        }

        // The depth layer is RESERVED in the layout but not produced yet. Report the no-depth
        // sentinel (rather than the reserved offset) so a consumer can tell "not implemented"
        // apart from "present". Turning depth on means writing the real offset + byte count
        // here and setting OV_FLAG_HAS_DEPTH — no layout change.
        overlayCtl.putInt(hdr + Proto.OV_SLOT_DEPTH_OFF, Proto.OV_NO_DEPTH);
        overlayCtl.putInt(hdr + Proto.OV_SLOT_DEPTH_BYTES, 0);

        // Bump framesPublished and set dirty bit (state bit 2).
        overlayCtl.putLong(Proto.OV_CTL_FRAMES, overlayFrames);
        int state = overlayCtl.getInt(Proto.OV_CTL_STATE);
        state = (state & ~3) | Proto.OVERLAY_DIRTY; // front slot 0 + dirty
        overlayCtl.putInt(Proto.OV_CTL_STATE, state);
        java.lang.invoke.VarHandle.fullFence();
        overlayCtl.putInt(Proto.OV_CTL_PAD, sequence);
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
        public long motionTimeMs, motionFrame;
        public int motionEpoch, contactFlags;
        public double velocityX, velocityY, velocityZ;
        public int flags, levelId, mapId, teleportSeq, viewW, viewH;
        public double posX, posY, posZ;
        public float yaw, pitch, gameHour;
    }

    public static final class McStateSnapshot {
        public long motionTimeMs;
        public int motionEpoch, motionMode;
        public double velocityX, velocityY, velocityZ;
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
        public int uid, flags,guid,nativeType,world,generation;
        public float x, y, z, yaw, width, height, healthFrac;
        public short level;
    }
}

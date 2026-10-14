package dev.stellarcraft.world;

import dev.stellarcraft.link.Proto;
import dev.stellarcraft.link.RingBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.item.BlockItem;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Sends "a block changed" to Stellar Blade so it can mirror the block as a <b>real actor</b>
 * in its own world.
 *
 * <h2>Why an actor and not an overlay pixel</h2>
 *
 * <p>Blocks used to be drawn into the exported frame, which made them a picture: no depth, no
 * occlusion, and — the part that matters — nothing at all for Stellar Blade's own characters
 * to collide with. The requirement is that a block is a physical object in Stellar Blade's
 * world: a Naytiba walking into it must be stopped by it.
 *
 * <p>That is what {@link Proto#EV_BLOCK_CHANGE} was reserved for in the protocol
 * ("a block changed: a/b/c = pos, flags = block state id (for SB-side NPC collision)"). This
 * class finally implements it; the Stellar Blade side ({@code main.lua}) spawns/removes an
 * {@code AStaticMeshActor} with a 1 m cube and {@code BlockAll} collision.
 *
 * <h2>Wire format</h2>
 * <pre>
 *   type  = EV_BLOCK_CHANGE
 *   uid   = unused
 *   a/b/c = the block's MIN CORNER in Minecraft world coordinates
 *   d     = unused
 *   flags = 1 place, 0 break, 2 remove everything (sent once before a full re-sync)
 * </pre>
 * The min corner (not the centre) is sent because it is an exact integer, so it doubles as a
 * stable key on the receiving side for matching a later break to the actor to destroy.
 */
public final class BlockBridge {

    // The flags values live in Proto (single source of truth for the protocol); main.lua has
    // the matching BLOCK_* names.
    private static final int ACTION_PLACE = Proto.EV_BLOCK_PLACE;
    private static final int ACTION_BREAK = Proto.EV_BLOCK_BREAK;
    private static final int ACTION_CLEAR_ALL = Proto.EV_BLOCK_CLEAR_ALL;

    /** Same ring the damage path uses (one MC -&gt; SB event channel). */
    public static volatile RingBuffer eventRing;
    private static long lastPlacementLog;
    private record Intent(net.minecraft.world.level.block.Block block,long until) {}
    private static final java.util.concurrent.ConcurrentHashMap<Long,Intent> intents=new java.util.concurrent.ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> nativeUse=ThreadLocal.withInitial(() -> false);
    private static long[] syncPositions=new long[0];
    private static int syncIndex,syncedPid,syncedWorld,syncedNativeWorld;
    private static volatile boolean mapClearPending;
    public static boolean isNativeUse() {return nativeUse.get();}

    private BlockBridge() {}

    public static void notePlacement() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.player.getMainHandItem().getItem() instanceof BlockItem)
                || !(mc.hitResult instanceof BlockHitResult hit)) return;
        long now=System.nanoTime();
        if (now-lastPlacementLog>1_000_000_000L) {
            lastPlacementLog=now;
            System.out.println("[StellarCraft-Blocks] use pick="+hit.getType()+" pos="+hit.getBlockPos()
                +" face="+hit.getDirection()+" nativeCells="+VirtualTerrain.count());
        }
        if (hit.getType()!=net.minecraft.world.phys.HitResult.Type.BLOCK) return;
    }

    public static boolean isPlacementReply(long packed,net.minecraft.world.level.block.state.BlockState state) {
        Intent intent=intents.remove(packed);
        return intent!=null && System.nanoTime()<=intent.until && state.is(intent.block);
    }

    /** Native builds use vanilla server game mode, ignoring only addressed hidden cells. */
    public static net.minecraft.world.InteractionResult useNativeBlock(net.minecraft.client.player.LocalPlayer player,
            net.minecraft.world.InteractionHand hand,BlockHitResult hit) {
        Minecraft mc=Minecraft.getInstance();var server=mc.getSingleplayerServer();
        if(!dev.stellarcraft.client.NativeMovement.owns(player) || !dev.stellarcraft.client.ControlStateMachine.isSteve()
            || server==null || !(player.getItemInHand(hand).getItem() instanceof BlockItem item)
            || hit.getType()!=net.minecraft.world.phys.HitResult.Type.BLOCK) return null;
        PlacedBlocks.ensureLevel(mc.level);
        BlockPos clicked=hit.getBlockPos();boolean nativeGround=!PlacedBlocks.contains(clicked.asLong());
        var context=new net.minecraft.world.item.context.BlockPlaceContext(player,hand,player.getItemInHand(hand),hit);
        BlockPos target=nativeGround ? clicked : context.getClickedPos();
        double offset=nativeGround ? hit.getLocation().y-target.getY() : PlacedBlocks.offset(clicked.asLong());
        PlacedBlocks.anchor(target.asLong(),offset);
        if(item instanceof net.minecraft.world.item.DoubleHighBlockItem) PlacedBlocks.anchor(target.above().asLong(),offset);
        int selectedSlot=player.getInventory().selected;
        long deadline=System.nanoTime()+2_000_000_000L;
        intents.put(target.asLong(),new Intent(item.getBlock(),deadline));
        if(item instanceof net.minecraft.world.item.DoubleHighBlockItem)
            intents.put(target.above().asLong(),new Intent(item.getBlock(),deadline));
        server.execute(() -> {
            var peer=server.getPlayerList().getPlayer(player.getUUID());
            if(peer==null || !dev.stellarcraft.client.NativeMovement.owns(peer)) return;
            peer.getInventory().selected=selectedSlot;
            var level=peer.serverLevel();
            if(!PlacedBlocks.contains(target.asLong())) level.setBlock(target,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),18);
            if(nativeGround) level.setBlock(clicked,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),18);
            nativeUse.set(true);
            try {
                var result=peer.gameMode.useItemOn(peer,level,peer.getItemInHand(hand),hand,hit);
                System.out.println("[StellarCraft-Blocks] native use target="+target+" result="+result);
            }
            finally {nativeUse.set(false);}
        });
        return net.minecraft.world.InteractionResult.SUCCESS;
    }

    /** Reports one block appearing or disappearing. Cheap enough for the packet thread. */
    public static void forward(long packed, boolean present) {
        if (eventRing == null) return;
        Minecraft mc = Minecraft.getInstance();
        var state=PlacedBlocks.state(packed);
        int model = present && state!=null ? BlockVisuals.export(BlockPos.of(packed),state) : 0;
        System.out.println("[StellarCraft-Blocks] "+(present?"place":"break")+" "+BlockPos.of(packed)+" model="+Integer.toUnsignedString(model));
        write(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed),
              present ? ACTION_PLACE : ACTION_BREAK, model);
        NativeWorldEvents.light(packed,present);
    }

    /**
     * Pushes the whole tracked set. Sent when Stellar Blade (re)takes the STEVE role, because
     * by then it may have been restarted and lost every actor it had spawned.
     *
     * <p>Clears first, so a Minecraft world change that reset the tracked set cannot leave
     * orphaned actors behind in Stellar Blade.
     */
    public static void syncAll() {
        Minecraft mc=Minecraft.getInstance();
        if (eventRing == null || mc.level==null) return;
        PlacedBlocks.ensureLevel(mc.level);
        var bridge=dev.stellarcraft.client.ClientBridge.get();
        int pid=bridge==null ? 0 : bridge.hostPid(), world=PlacedBlocks.worldId();
        int nativeWorld=bridge==null ? 0 : bridge.hostWorldToken();
        if(nativeWorld==0) {syncPositions=new long[0];syncIndex=0;return;}
        if(mapClearPending) return;
        int mapId=bridge.hostMapId();
        var previous=PlacedBlocks.enterNativeMap(mapId);
        if(previous!=null) {
            syncPositions=new long[0];syncIndex=0;intents.clear();
            var server=mc.getSingleplayerServer();
            var dimension=mc.level.dimension();
            if(server!=null) {
                mapClearPending=true;
                server.execute(()->{
                    try {
                        var level=server.getLevel(dimension);
                        if(level!=null) {
                            NativeEntities.clearForMapChange(level);
                            for(var entry:previous.entrySet()) {
                                var pos=BlockPos.of(entry.getKey());
                                if(level.getBlockState(pos).is(entry.getValue().getBlock()))
                                    level.setBlock(pos,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),2);
                            }
                        }
                    } finally {mapClearPending=false;}
                });
            }
            System.out.println("[StellarCraft-Map] cleared old map ledger blocks="+previous.size()+" epoch="+PlacedBlocks.nativeEpoch());
            return;
        }
        if(syncedPid==pid && syncedWorld==world && syncedNativeWorld==nativeWorld) return;
        syncedPid=pid;syncedWorld=world;syncedNativeWorld=nativeWorld;
        write(0,0,0,3,world);
        write(0, 0, 0, ACTION_CLEAR_ALL, 0);
        syncPositions=PlacedBlocks.positions();syncIndex=0;
        System.out.println("[StellarCraft-Blocks] sync queued="+syncPositions.length+" world="+Integer.toUnsignedString(world));
    }
    public static void tick(Minecraft mc) {
        PlacedBlocks.ensureLevel(mc.level);PlacedBlocks.tick();
        if(mc.level==null) return;
        syncAll();
        if(mapClearPending) return;
        for(int n=0;n<16 && syncIndex<syncPositions.length;n++) {
            long packed=syncPositions[syncIndex++];if(PlacedBlocks.contains(packed)) forward(packed,true);
        }
        if(intents.size()>128) intents.entrySet().removeIf(e->e.getValue().until<System.nanoTime());
    }

    private static void write(int x, int y, int z, int action, int model) {
        int nativeWorld=NativeWorldEvents.worldToken();
        if(nativeWorld==0) return;
        ByteBuffer ev = ByteBuffer.allocate(Proto.MC_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        ev.putInt(Proto.EV_BLOCK_CHANGE);
        ev.putInt(model);             // unsigned CRC32 of native model asset; zero = no model
        ev.putFloat((float) x);
        ev.putFloat((float) y);
        ev.putFloat((float) z);
        ev.putFloat((float)PlacedBlocks.offset(net.minecraft.core.BlockPos.asLong(x,y,z)));
        ev.putInt(action);
        ev.putInt(nativeWorld);       // world event: native generation, not a combat weapon
        ev.flip();

        long claimed = eventRing.claim(Proto.MC_EVENT_SIZE);
        if (claimed < 0) return;      // ring full; drop rather than block the caller
        eventRing.writeBytes(claimed, ev);
        eventRing.publish(claimed, Proto.MC_EVENT_SIZE);
    }
}

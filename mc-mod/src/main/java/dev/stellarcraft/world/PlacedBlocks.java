package dev.stellarcraft.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

/** Persistent native build ledger, scoped to the singleplayer save and dimension. */
public final class PlacedBlocks {
    private static final LinkedHashMap<Long,BlockState> blocks=new LinkedHashMap<>();
    private static final Map<Long,Double> offsets=new HashMap<>();
    private static ClientLevel owner;
    private static Path file;
    private static int worldId;
    private static boolean dirty;
    private static long lastSaved;
    private PlacedBlocks() {}
    public static synchronized void ensureLevel(ClientLevel level) {
        if(level==owner) return;
        save();owner=level;blocks.clear();offsets.clear();file=null;dirty=false;
        var server=Minecraft.getInstance().getSingleplayerServer();
        if(level==null || server==null) return;
        Path root=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
        String dimension=level.dimension().location().toString();
        CRC32 crc=new CRC32();crc.update((root+"#"+dimension).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        worldId=(int)crc.getValue();
        file=root.resolve("stellarcraft-"+Integer.toUnsignedString(worldId)+".nbt");
        try {
            if(Files.exists(file)) {
                CompoundTag data=NbtIo.readCompressed(file.toFile());ListTag list=data.getList("Blocks",10);
                for(int i=0;i<Math.min(list.size(),8192);i++) {
                    CompoundTag entry=list.getCompound(i);
                    BlockState state=NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK),entry.getCompound("State"));
                    if(!state.isAir()) {
                        long pos=entry.getLong("Pos");blocks.put(pos,state);
                        double offset=entry.getDouble("NativeYOffset");
                        if(Double.isFinite(offset)&&Math.abs(offset)<=1) offsets.put(pos,offset);
                    }
                }
            }
            System.out.println("[StellarCraft-Blocks] loaded ledger="+Integer.toUnsignedString(worldId)+" count="+blocks.size());
        } catch(Exception error) {System.out.println("[StellarCraft-Blocks] ledger load failed: "+error);}
    }
    public static synchronized boolean record(Object level,long packed,boolean present) {
        ensureLevel((ClientLevel)level);
        BlockState state=present ? owner.getBlockState(net.minecraft.core.BlockPos.of(packed)) : null;
        BlockState previous=state==null ? blocks.remove(packed) : blocks.put(packed,state);
        boolean changed=previous!=state;
        if(blocks.size()>8192) blocks.remove(blocks.keySet().iterator().next());
        if(changed) dirty=true;return changed;
    }
    public static synchronized long[] positions() {return blocks.keySet().stream().mapToLong(Long::longValue).toArray();}
    public static synchronized int count() {return blocks.size();}
    public static synchronized int worldId() {return worldId;}
    public static synchronized boolean contains(long packed) {return blocks.containsKey(packed);}
    public static synchronized BlockState state(long packed) {return blocks.get(packed);}
    public static synchronized double offset(long packed) {return offsets.getOrDefault(packed,0.0);}
    public static synchronized void anchor(long packed,double offset) {
        if(Double.isFinite(offset)&&Math.abs(offset)<=1) {offsets.put(packed,offset);dirty=true;}
    }
    public static synchronized void tick() {if(dirty && System.nanoTime()-lastSaved>500_000_000L) save();}
    public static synchronized void save() {
        if(!dirty || file==null) return;
        try {
            CompoundTag data=new CompoundTag();ListTag list=new ListTag();
            for(var block:blocks.entrySet()) {
                CompoundTag entry=new CompoundTag();entry.putLong("Pos",block.getKey());
                entry.put("State",NbtUtils.writeBlockState(block.getValue()));list.add(entry);
                entry.putDouble("NativeYOffset",offset(block.getKey()));
            }
            data.put("Blocks",list);Path temporary=file.resolveSibling(file.getFileName()+".tmp");
            NbtIo.writeCompressed(data,temporary.toFile());Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);
            dirty=false;lastSaved=System.nanoTime();
        } catch(Exception error) {System.out.println("[StellarCraft-Blocks] ledger save failed: "+error);}
    }
}

import dev.stellarcraft.world.PlacedBlocks;
import dev.stellarcraft.world.NativeEntities;
import net.minecraft.nbt.NbtIo;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.util.*;

/** Actual ledger persistence and entity epoch gate; no client/server or game starts. */
public class NativeMapClearTest {
    private static Field field(Class<?> type,String name) throws Exception {
        Field f=type.getDeclaredField(name);f.setAccessible(true);return f;
    }
    private static void check(boolean value,String message) {if(!value) throw new AssertionError(message);}
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var blocks=(Map<Long,Object>)field(PlacedBlocks.class,"blocks").get(null);
        var offsets=(Map<Long,Double>)field(PlacedBlocks.class,"offsets").get(null);
        blocks.put(71L,null);offsets.put(71L,.5); // Poisoned state: clear must not dereference it.
        check(PlacedBlocks.enterNativeMap(101)==null && PlacedBlocks.count()==1,"first observation erased current map");
        check(PlacedBlocks.enterNativeMap(0)==null && PlacedBlocks.enterNativeMap(101)==null
            && PlacedBlocks.count()==1,"loading/F4/same map erased content");
        var file=Files.createTempFile("stellarcraft-map-clear-",".nbt");
        field(PlacedBlocks.class,"file").set(null,file);
        try {
            var removed=PlacedBlocks.enterNativeMap(202);
            check(removed!=null && removed.containsKey(71L) && PlacedBlocks.count()==0
                && PlacedBlocks.offset(71L)==0,"map transition did not clear ledger/offsets");
            var disk=NbtIo.readCompressed(file.toFile());
            check(disk.getList("Blocks",10).isEmpty() && disk.getInt("NativeMapId")==202
                && disk.getLong("NativeEpoch")==1,"empty ledger/map generation not persisted");
            blocks.put(72L,null);offsets.put(72L,-.2);
            check(PlacedBlocks.enterNativeMap(202)==null && PlacedBlocks.count()==1,"same map reset newly placed block");
            check(PlacedBlocks.enterNativeMap(101).containsKey(72L) && PlacedBlocks.count()==0
                && PlacedBlocks.nativeEpoch()==2,"returning to old map restored its old blocks");
            check(PlacedBlocks.enterNativeMap(303)!=null && PlacedBlocks.nativeEpoch()==3,
                "map change with zero blocks must still invalidate creatures");
            var gate=NativeEntities.class.getDeclaredMethod("acceptsEpoch",Set.class,long.class);gate.setAccessible(true);
            check((boolean)gate.invoke(null,Set.of("stellarcraft.native"),0L),"legacy native entity rejected before first map change");
            check(!(boolean)gate.invoke(null,Set.of("stellarcraft.native"),3L),"legacy entity reappeared from unloaded chunk");
            check(!(boolean)gate.invoke(null,Set.of("stellarcraft.native","stellarcraft.epoch.2"),3L),"old saved creature accepted");
            check((boolean)gate.invoke(null,Set.of("stellarcraft.native","stellarcraft.epoch.3"),3L),"new map creature rejected");
            var old=new NativeEntities.Snapshot(1,UUID.randomUUID(),0,0,0,0,0,0,1,1,0,0,0,0,0,0,0,0,0);
            field(NativeEntities.class,"snapshot").set(null,List.of(old));
            field(NativeEntities.class,"snapshotEpoch").setLong(null,2);
            check(NativeEntities.snapshots().isEmpty(),"previous server tick rendered old creatures after map change");
            System.out.println("PASS: actual map ledger cleared/persisted; first/same/zero map preserved; returning map and empty-map cleanup; old creature epochs and snapshots rejected, new creatures accepted");
        } finally {Files.deleteIfExists(file);Files.deleteIfExists(file.resolveSibling(file.getFileName()+".tmp"));}
    }
}

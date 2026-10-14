import dev.stellarcraft.client.ClientBridge;
import dev.stellarcraft.link.Proto;
import dev.stellarcraft.link.RingBuffer;
import dev.stellarcraft.link.SharedMemory;
import dev.stellarcraft.world.BlockBridge;
import dev.stellarcraft.world.NativeWorldEvents;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Real publisher and seqlock snapshot, with local buffers; no Minecraft launched. */
public class WorldEventEpochTest {
    private static void check(boolean value) {if(!value) throw new AssertionError();}
    public static void main(String[] args) {
        var mapping=ByteBuffer.allocateDirect((int)Proto.MAPPING_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        ClientBridge.start(new SharedMemory(mapping));
        var region=ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        var ring=new RingBuffer(region,0,64,128,128);
        BlockBridge.eventRing=ring;
        int at=(int)Proto.OFF_SB_STATE;
        check(NativeWorldEvents.worldToken()==0);
        NativeWorldEvents.send(3,0,1,2,3,4,0,12);check(ring.head()==0);
        mapping.putInt(at+Proto.SB_SEQ,2).putInt(at+Proto.SB_FLAGS,Proto.SbFlags.IN_GAME)
            .putInt(at+Proto.SB_LEVEL_ID,12);
        check(NativeWorldEvents.worldToken()==12);
        mapping.putInt(at+Proto.SB_MAP_ID,0x12345678);
        check(ClientBridge.get().hostMapId()==0x12345678);
        NativeWorldEvents.send(3,0,1,2,3,4,0,11);check(ring.head()==0);
        NativeWorldEvents.send(3,0,1,2,3,4,0,12);check(ring.head()==32);
        var packet=ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        ring.readBytes(0,32,packet);check(packet.getInt(0)==3 && packet.getInt(28)==12);
        mapping.putInt(at+Proto.SB_LEVEL_ID,13);
        check(ClientBridge.get().hostMapId()==0x12345678);
        NativeWorldEvents.send(8,0,1,2,3,0,0,12);check(ring.head()==32);
        NativeWorldEvents.send(8,0,1,2,3,0,0,13);check(ring.head()==64);
        mapping.putInt(at+Proto.SB_FLAGS,0);
        NativeWorldEvents.send(7,0,1,2,3,4,0);check(ring.head()==64);
        mapping.putInt(at+Proto.SB_FLAGS,Proto.SbFlags.IN_GAME).putInt(at+Proto.SB_SEQ,3);
        check(NativeWorldEvents.worldToken()==0);
        mapping.putInt(at+Proto.SB_SEQ,4).putInt(at+Proto.SB_LEVEL_ID,0xFEDCBA98);
        NativeWorldEvents.send(7,0,1,2,3,4,0);check(ring.head()==96);
        ring.readBytes(64,32,packet);check(packet.getInt(28)==0xFEDCBA98);
        System.out.println("PASS: actual world-event publisher; old/zero/inactive/mid-write tokens rejected; raw unsigned token preserved; delayed pearl rejected after rebind");
    }
}

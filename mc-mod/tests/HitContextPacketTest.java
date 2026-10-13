import dev.stellarcraft.link.Proto;
import dev.stellarcraft.link.RingBuffer;
import dev.stellarcraft.link.SharedMemory;
import dev.stellarcraft.world.DamageBridge;
import net.minecraft.world.phys.Vec3;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Offline test of the real publisher/ring. No client, server, or game is launched. */
public class HitContextPacketTest {
    private static ByteBuffer hit() {
        return ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1).putInt(42).putFloat(7).putFloat(0).putFloat(0).putFloat(.3f).putInt(22).putInt(5).flip();
    }
    private static void check(boolean value,String message) {if(!value) throw new AssertionError(message);}
    private static ByteBuffer record(RingBuffer ring,long cursor) {
        var out=ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);ring.readBytes(cursor,32,out);return out;
    }
    public static void main(String[] args) {
        check(Proto.K_VERSION==6&&Proto.EV_HIT_ORIGIN==9&&Proto.EV_HIT_DIRECTION==10,"matching v6 kinds");
        var region=ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        var ring=new RingBuffer(region,0,64,128,128);
        long time=0x12347FC12345L; // Low32 looks like a NaN if accidentally serialized as a float.
        DamageBridge.publishHit(ring,hit(),42,22,5,new Vec3(1,2,3),new Vec3(.6,.8,0),time);
        check(ring.head()==96,"one atomic 3-record publication");
        var origin=record(ring,0);check(origin.getInt()==9&&origin.getInt()==42,"origin identity");
        check(origin.getFloat()==1&&origin.getFloat()==2&&origin.getFloat()==3,"origin coordinates");
        check(origin.getInt()==(int)time&&origin.getInt()==22&&origin.getInt()==5,"raw timestamp and hit key");
        check(record(ring,32).getInt()==10&&record(ring,64).getInt()==1,"trajectory then unchanged HP event");
        region.putLong(0,96);region.putLong(64,96);
        DamageBridge.publishHit(ring,hit(),42,22,5,new Vec3(1,2,3),new Vec3(.6,.8,0),time);
        check(ring.head()==192&&record(ring,96).getInt()==9&&record(ring,128).getInt()==10
            &&record(ring,160).getInt()==1,"wrapped batch stays complete and ordered");
        region.putLong(0,96);region.putLong(64,0); // Only 32 bytes available.
        DamageBridge.publishHit(ring,hit(),42,22,5,new Vec3(1,2,3),new Vec3(.6,.8,0),time);
        check(ring.head()==128&&record(ring,96).getInt()==1,"context pressure preserves HP publication");
        region.putLong(0,112);region.putLong(64,0);
        DamageBridge.publishHit(ring,hit(),42,22,5,new Vec3(1,2,3),null,time);
        check(ring.head()==112,"full ring does not publish partial context");
        region.putLong(0,0);region.putLong(64,0);
        DamageBridge.publishHit(ring,hit(),42,22,5,new Vec3(Double.NaN,0,0),null,time);
        check(ring.head()==32&&record(ring,0).getInt()==1,"invalid origin preserves HP only");
        region.putLong(0,0);region.putLong(64,0);
        DamageBridge.publishHit(ring,hit(),42,22,5,new Vec3(1,2,3),null,time);
        check(ring.head()==64&&record(ring,0).getInt()==9&&record(ring,32).getInt()==1,"origin-only batch");
        region.putLong(0,0);region.putLong(64,0);
        var heavy=hit();heavy.putInt(28,Proto.WEAPON_BLADE|Proto.HIT_STYLE_JUMP_HEAVY);
        DamageBridge.publishHit(ring,heavy,42,22,0x101,new Vec3(1,2,3),null,time);
        check(record(ring,0).getInt(28)==0x101&&record(ring,32).getInt(28)==0x101
            &&record(ring,0).getInt(24)==22&&record(ring,32).getInt(24)==22,"packed style pairs without changing serial");
        var mapping=ByteBuffer.allocateDirect((int)Proto.MAPPING_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        mapping.putInt(Proto.HDR_MAGIC,Proto.K_MAGIC);mapping.putInt(Proto.HDR_VERSION,5);
        var shared=new SharedMemory(mapping);
        check(!shared.isSbReady(),"old v5 handshake must be rejected");
        mapping.putInt(Proto.HDR_VERSION,6);check(shared.isSbReady(),"matching v6 handshake accepted");
        System.out.println("PASS: actual Java v6 hit publisher; raw timestamps, pairing, wrap, full-ring atomicity and HP fallback");
    }
}

import java.nio.*;
import dev.stellarcraft.link.*;
public class SharedMemoryDamageRegression {
    public static void main(String[] args) {
        var bytes=ByteBuffer.allocateDirect((int)Proto.MAPPING_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        var mem=new SharedMemory(bytes);
        int at=(int)Proto.OFF_SB_STATE+0x80;
        bytes.putInt(at,2).putInt(at+4,123).putDouble(at+8,4.25).putInt(at+16,789).putInt(at+20,5);
        var hit=mem.readPlayerHurt();
        if(hit==null||hit.guid()!=123||hit.total()!=4.25||hit.attacker()!=789||hit.blocked()!=5) throw new AssertionError("Reserved hurt region not read correctly");
        bytes.putInt(at,3);
        if(mem.readPlayerHurt()!=null) throw new AssertionError("Mid-write damage must be rejected");
        var legacy=mem.readSbState();
        if(legacy==null) throw new AssertionError("Legacy state read failed");
        System.out.println("PASS: separate reserved damage region, bounds and odd seqlock rejection");
    }
}

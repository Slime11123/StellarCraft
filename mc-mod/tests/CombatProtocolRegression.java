import dev.stellarcraft.link.*;
import dev.stellarcraft.NativeLink;
import java.nio.*;
public class CombatProtocolRegression {
    static void check(boolean condition,String label) {if(!condition) throw new AssertionError(label);}
    public static void main(String[] args) {
        var buffer=ByteBuffer.allocateDirect((int)Proto.MAPPING_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(8,1234);var memory=new SharedMemory(buffer);long now=NativeLink.tickCount();
        int table=(int)Proto.OFF_ACTOR_TABLE;
        buffer.putInt(table,2);buffer.putInt(table+4,1);buffer.putLong(table+8,now);
        buffer.putInt(table+16,1234);buffer.putInt(table+20,55);
        int actor=table+64;buffer.putInt(actor,77);buffer.putInt(actor+4,1);
        buffer.putFloat(actor+24,4);buffer.putFloat(actor+28,6);buffer.putFloat(actor+32,.75f);
        buffer.putInt(actor+40,901);buffer.putInt(actor+44,501);buffer.putInt(actor+48,55);
        var rows=memory.readActors(55);check(rows.size()==1&&rows.get(0).guid==901&&rows.get(0).width==4,"identity/dynamic dimensions");
        buffer.putInt(table,3);check(memory.readActors(55)==null,"half actor frame");buffer.putInt(table,4);
        check(memory.readActors(56).isEmpty(),"world isolation");buffer.putLong(table+8,now-1000);
        check(memory.readActors(55).isEmpty(),"stale actor frame");buffer.putLong(table+8,now);
        int hit=(int)Proto.OFF_COMBAT+128+Proto.COMBAT_HIT_SIZE;
        buffer.putInt(hit,2);buffer.putInt(hit+4,1);buffer.putLong(hit+8,1);buffer.putLong(hit+16,now);
        buffer.putInt(hit+24,228);buffer.putInt(hit+28,70001);buffer.putInt(hit+32,1234);buffer.putInt(hit+36,55);
        buffer.putFloat(hit+40,4);buffer.putInt(hit+44,901);buffer.putDouble(hit+48,10);buffer.putDouble(hit+56,20);buffer.putDouble(hit+64,30);
        var h=memory.readCombatHit(1);check(h!=null&&h.directional()&&h.rawSource()!=h.sourceGuid()&&h.x()==10,"per-hit verified direction");
        check(memory.readCombatHit(129)==null,"overwritten/replayed event slot");
        buffer.putInt(hit,3);check(memory.readCombatHit(1)==null,"half hit frame");buffer.putInt(hit,4);
        buffer.putInt(hit+32,2222);check(memory.readCombatHit(1)==null,"foreign process");buffer.putInt(hit+32,1234);
        buffer.putLong(hit+16,now-1000);check(memory.readCombatHit(1)==null,"expired hit");buffer.putLong(hit+16,now);
        buffer.putDouble(hit+48,Double.NaN);check(memory.readCombatHit(1)==null,"invalid direction");buffer.putDouble(hit+48,10);
        buffer.putFloat(hit+40,Float.NaN);check(memory.readCombatHit(1)==null,"invalid damage");buffer.putFloat(hit+40,4);
        buffer.putInt(hit+4,0);buffer.putInt(hit+44,0);check(!memory.readCombatHit(1).directional(),"unknown source cannot gain direction");
        System.out.println("PASS: combat identity/size, process/world/freshness, half writes, replay slot and verified source direction");
    }
}

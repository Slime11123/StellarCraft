package dev.stellarcraft.world;

import dev.stellarcraft.link.Proto;
import java.nio.*;

/** Called only on the MC client thread, sharing the existing SPSC event producer. */
public final class NativeWorldEvents {
    private NativeWorldEvents() {}
    public static void send(int type,int uid,double x,double y,double z,double value,int flags) {
        var ring=BlockBridge.eventRing;if(ring==null) return;
        ByteBuffer b=ByteBuffer.allocate(Proto.MC_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(type).putInt(uid).putFloat((float)x).putFloat((float)y).putFloat((float)z).putFloat((float)value).putInt(flags).putInt(0).flip();
        long offset=ring.claim(Proto.MC_EVENT_SIZE);if(offset<0) return;
        ring.writeBytes(offset,b);ring.publish(offset,Proto.MC_EVENT_SIZE);
    }
    public static void light(long packed,boolean present) {
        var state=PlacedBlocks.state(packed);int light=present&&state!=null?state.getLightEmission():0;
        send(7,0,net.minecraft.core.BlockPos.getX(packed),net.minecraft.core.BlockPos.getY(packed),net.minecraft.core.BlockPos.getZ(packed),light,
            Float.floatToRawIntBits((float)PlacedBlocks.offset(packed)));
    }
}

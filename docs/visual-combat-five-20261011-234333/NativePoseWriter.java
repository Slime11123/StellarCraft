package dev.stellarcraft.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;

/** Two file slots, a sequence footer and CRC let native readers reject concurrent writes. */
final class NativePoseWriter {
    private static final java.util.concurrent.atomic.AtomicInteger sequence=new java.util.concurrent.atomic.AtomicInteger();
    void publish(Path dir, String stem, List<HandMeshBridge.Face> faces) throws Exception {
        var player=net.minecraft.client.Minecraft.getInstance().player;
        if(player==null) return;
        publish(stem.equals("hands")?0:stem.equals("body")?1:2,0,player.getUUID(),faces);
    }
    void publish(int slot,int entity,java.util.UUID uuid,List<HandMeshBridge.Face> faces) throws Exception {
        var bridge=dev.stellarcraft.client.ClientBridge.get();if(bridge==null) return;
        int seq=sequence.incrementAndGet();
        ByteBuffer output=ByteBuffer.allocate(32+faces.size()*44).order(ByteOrder.LITTLE_ENDIAN);
        output.put("SCPOSE1\n".getBytes(StandardCharsets.US_ASCII));
        output.putInt(seq).putLong(System.currentTimeMillis()).putInt(faces.size());
        for (var face:faces) {
            output.putInt(face.model());
            for (float value:face.transform()) output.putFloat(value);
        }
        CRC32 crc=new CRC32(); crc.update(output.array(),0,output.position());
        output.putInt(seq).putInt((int)crc.getValue());
        bridge.publishPose(slot,entity,uuid,output.array());
    }
}

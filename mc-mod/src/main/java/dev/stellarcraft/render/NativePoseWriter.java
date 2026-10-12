package dev.stellarcraft.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;

/** Seqlocked shared-memory publication with a sequence footer and CRC. */
final class NativePoseWriter {
    private static final java.util.concurrent.atomic.AtomicInteger sequence=new java.util.concurrent.atomic.AtomicInteger();
    private List<Integer> topology=List.of();
    private int generation;
    void publish(Path dir, String stem, List<HandMeshBridge.Face> faces) throws Exception {
        var player=net.minecraft.client.Minecraft.getInstance().player;
        if(player==null) return;
        publish(stem.equals("hands")?0:stem.equals("body")?1:2,0,player.getUUID(),faces);
    }
    void publish(int slot,int entity,java.util.UUID uuid,List<HandMeshBridge.Face> faces) throws Exception {
        var bridge=dev.stellarcraft.client.ClientBridge.get();if(bridge==null) return;
        var next=faces.stream().map(HandMeshBridge.Face::model).toList();
        // Persist topology generation in every frame, including frames after a dropped
        // replacement frame. Lower 24 bits retain the ordinary publication sequence.
        if(!next.equals(topology)) generation=(generation+1)&255;
        int seq=(sequence.incrementAndGet()&0x00ffffff)|(generation<<24);
        topology=next;
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

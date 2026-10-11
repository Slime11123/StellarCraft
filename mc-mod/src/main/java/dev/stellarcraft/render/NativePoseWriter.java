package dev.stellarcraft.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;

/** Two file slots, a sequence footer and CRC let native readers reject concurrent writes. */
final class NativePoseWriter {
    private int sequence;
    void publish(Path dir, String stem, List<HandMeshBridge.Face> faces) throws Exception {
        int seq=++sequence;
        ByteBuffer output=ByteBuffer.allocate(32+faces.size()*44).order(ByteOrder.LITTLE_ENDIAN);
        output.put("SCPOSE1\n".getBytes(StandardCharsets.US_ASCII));
        output.putInt(seq).putLong(System.currentTimeMillis()).putInt(faces.size());
        for (var face:faces) {
            output.putInt(face.model());
            for (float value:face.transform()) output.putFloat(value);
        }
        CRC32 crc=new CRC32(); crc.update(output.array(),0,output.position());
        output.putInt(seq).putInt((int)crc.getValue());
        Files.write(dir.resolve(stem+"."+(seq&1)+".frame"),output.array());
    }
}

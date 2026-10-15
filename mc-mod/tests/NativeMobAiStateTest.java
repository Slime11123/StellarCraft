package dev.stellarcraft.world;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.NbtIo;
import java.io.*;

/** Offline NBT reload reproduces the loss of original NoAI after suppression. */
public final class NativeMobAiStateTest {
    private static void check(boolean value,String message) {if(!value) throw new AssertionError(message);}
    private static void reload(boolean originalNoAi) throws Exception {
        Set<String> tags=new HashSet<>();
        String remembered=NativeMobAiState.captureTag(tags,false,originalNoAi);
        check(remembered.equals("stellarcraft.ai.original.noai_"+originalNoAi),"original NBT NoAI lost");
        tags.add(remembered);tags.add("stellarcraft.native");tags.add("stellarcraft.epoch.7");tags.add("user-marker");
        CompoundTag saved=new CompoundTag();ListTag list=new ListTag();
        for(String tag:tags) list.add(StringTag.valueOf(tag));
        saved.put("Tags",list);saved.putBoolean("NoAI",true);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(var output=new DataOutputStream(bytes)) {NbtIo.write(saved,output);}
        CompoundTag loaded;
        try(var input=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {loaded=NbtIo.read(input);}
        Set<String> restored=new HashSet<>();
        for(var tag:loaded.getList("Tags",8)) restored.add(tag.getAsString());
        check(loaded.getBoolean("NoAI"),"test did not persist the forced NoAI value");
        check(NativeMobAiState.captureTag(restored,true,loaded.getBoolean("NoAI"))==null,
            "reload overwrote original provenance with the forced value");
        check(restored.equals(tags),"epoch or user tags changed across NBT reload");
    }
    public static void main(String[] args) throws Exception {
        reload(false);reload(true);
        for(boolean currentNoAi:new boolean[]{false,true}) {
            check(NativeMobAiState.captureTag(Set.of("stellarcraft.native"),true,currentNoAi)
                .equals("stellarcraft.ai.original.unknown"),"legacy provenance was guessed");
            check(NativeMobAiState.captureTag(Set.of("stellarcraft.ai.original.unknown"),true,currentNoAi)==null,
                "unknown provenance was upgraded on reload");
        }
        check(NativeMobAiState.captureTag(Set.of("stellarcraft.ai.original.invalid"),false,false)==null,
            "invalid provenance was replaced with an enabled marker");
        check(NativeMobAiState.captureTag(Set.of("stellarcraft.ai.original.noai_true","stellarcraft.ai.original.noai_false"),true,true)==null,
            "conflicting provenance was silently repaired");
        System.out.println("PASS: original true/false NoAI survives forced-NoAI NBT reload; legacy/unknown/corrupt provenance is never guessed; unrelated tags preserved");
    }
}

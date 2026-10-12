package dev.stellarcraft.world;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import java.nio.file.*;

/** Opt-in local integration harness. Disabled unless launched with -Dstellarcraft.devQa=true. */
public final class NativeQa {
    private static String last="";
    private static int heldTicks;
    private static net.minecraft.client.KeyMapping held;
    private NativeQa() {}
    public static void tick(Minecraft mc) {
        if(!Boolean.getBoolean("stellarcraft.devQa")||mc.player==null||mc.getSingleplayerServer()==null) return;
        if(held!=null) {held.setDown(heldTicks-->0);if(heldTicks<0) {held=null;System.out.println("[StellarCraft-QA] held input completed");}}
        try {
            Path path=mc.gameDirectory.toPath().resolve("config/stellarcraft-dev-qa.txt");
            if(!Files.exists(path)) return;String command=Files.readString(path).strip();
            if(command.equals(last)) return;last=command;String[] words=command.split(" ");
            if(words.length==3&&words[1].equals("equip")&&words[2].matches("minecraft:[a-z_]+")) {
                var item=BuiltInRegistries.ITEM.get(new ResourceLocation(words[2]));
                mc.player.getInventory().setItem(0,new ItemStack(item,64));mc.player.getInventory().selected=0;
                var id=mc.player.getUUID();mc.getSingleplayerServer().execute(()->{
                    var peer=mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                    if(peer!=null) {peer.getInventory().setItem(0,new ItemStack(item,64));peer.getInventory().selected=0;peer.inventoryMenu.broadcastChanges();}
                });
            } else if(words.length==3&&words[1].equals("mode")) {
                var mode=net.minecraft.world.level.GameType.byName(words[2]);var id=mc.player.getUUID();
                mc.getSingleplayerServer().execute(()->{var peer=mc.getSingleplayerServer().getPlayerList().getPlayer(id);if(peer!=null) peer.setGameMode(mode);});
            } else if(words.length==4&&words[1].equals("look")) {
                mc.player.setYRot(Float.parseFloat(words[2]));mc.player.setXRot(Float.parseFloat(words[3]));
            } else if(words.length==4&&words[1].equals("hold")) {
                if(held!=null) held.setDown(false);
                held=switch(words[2]) {case "forward"->mc.options.keyUp;case "back"->mc.options.keyDown;case "shift"->mc.options.keyShift;case "jump"->mc.options.keyJump;case "use"->mc.options.keyUse;default->null;};
                heldTicks=Math.max(0,Math.min(100,Integer.parseInt(words[3])));
            } else if(words.length==2&&words[1].equals("restoreHealth")) {
                var id=mc.player.getUUID();mc.getSingleplayerServer().execute(()->{
                    var peer=mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                    if(peer!=null) {peer.setHealth(peer.getMaxHealth());peer.getFoodData().setFoodLevel(20);}
                });
            } else if(words.length==6&&words[1].equals("recoverNative")) {
                var uuid=java.util.UUID.fromString(words[2]);
                double x=Double.parseDouble(words[3]),y=Double.parseDouble(words[4]),z=Double.parseDouble(words[5]);
                if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
                    ||Math.abs(x-mc.player.getX())>32||Math.abs(z-mc.player.getZ())>32||Math.abs(y-mc.player.getY())>5) return;
                mc.getSingleplayerServer().execute(()->{
                    for(var level:mc.getSingleplayerServer().getAllLevels()) {
                        var entity=level.getEntity(uuid);
                        if(entity!=null&&NativeEntities.owns(entity)) {entity.setPos(x,y,z);entity.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);}
                    }
                });
            } else if(words.length==2&&words[1].equals("damageIsolation")) {
                var id=mc.player.getUUID();mc.getSingleplayerServer().execute(()->{
                    var peer=mc.getSingleplayerServer().getPlayerList().getPlayer(id);if(peer==null) return;
                    var hidden=net.minecraft.world.entity.EntityType.ZOMBIE.create(peer.serverLevel());
                    float before=peer.getHealth();boolean hurt=peer.hurt(peer.damageSources().mobAttack(hidden),4);
                    System.out.println("[StellarCraft-QA] hidden mob hurt="+hurt+" health="+before+" -> "+peer.getHealth());
                });
            } else if(words.length==2&&words[1].equals("state")) {
                var p=mc.player;
                System.out.println("[StellarCraft-QA] player="+p.position()+" health="+p.getHealth()+" pose="+p.getPose()
                    +" ground="+p.onGround()+" glide="+p.isFallFlying()+" velocity="+p.getDeltaMovement()
                    +" chest="+p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)
                    +" held="+p.getMainHandItem()+" pick="+mc.hitResult+" entities="+NativeEntities.snapshots()+" ledger="+PlacedBlocks.count());
            }
            System.out.println("[StellarCraft-QA] "+command);
        }catch(Exception error){System.out.println("[StellarCraft-QA] failed: "+error);}
    }
}

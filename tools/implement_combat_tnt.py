from pathlib import Path
p=Path('mc-mod/src/main/java/dev/stellarcraft/world/NativeEntities.java');s=p.read_text();a=s.index('        Minecraft.getInstance().execute(()->{\n            NativeWorldEvents.send(3,0,center');b=s.index('        System.out.println("[StellarCraft-TNT]',a);s=s[:a]+'''        ExplosionDamageSink.blast(t.getUUID(),level,center);
        Minecraft.getInstance().execute(()->NativeWorldEvents.send(3,0,center.x,center.y,center.z,radius,0));
'''+s[b:];p.write_text(s)
p=Path('mc-mod/src/main/java/dev/stellarcraft/world/mixin/ExplosionMixin.java');s=p.read_text();a=s.index('        if (level == null');s=s[:a]+'''        // Registered TNT uses NativeEntities.explode only. Hidden/vanilla MC
        // explosions are deliberately not forwarded into the native scene.
    }
}
''';p.write_text(s)
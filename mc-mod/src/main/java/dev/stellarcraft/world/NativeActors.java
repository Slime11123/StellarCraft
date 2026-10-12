package dev.stellarcraft.world;

import dev.stellarcraft.client.ControlStateMachine;
import dev.stellarcraft.link.SharedMemory;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import java.util.*;

/** One server-owned proxy per current native identity; vanilla networking supplies clients. */
public final class NativeActors {
    private NativeActors() {}
    private record Frame(int pid,int world,long received,List<SharedMemory.ActorSnapshot> actors) {}
    private static volatile Frame frame;
    private static final Map<Integer,ActorProxy> proxies=new HashMap<>();
    private static int pid,world;
    public static void publish(int hostPid,List<SharedMemory.ActorSnapshot> actors) {
        frame=new Frame(hostPid,PlacedBlocks.worldId(),System.currentTimeMillis(),List.copyOf(actors));
    }
    public static void clear() {frame=null;}
    public static boolean current(ActorProxy proxy) {
        var f=frame;
        return f!=null&&System.currentTimeMillis()-f.received<500&&ControlStateMachine.isSteve()
            &&proxy.nativePid()==f.pid&&proxy.nativeWorld()==f.world&&proxy.isNativeLive();
    }
    public static void init() { ServerTickEvents.END_SERVER_TICK.register(server->{
        var f=frame;var mc=Minecraft.getInstance();
        var player=mc.player==null?null:server.getPlayerList().getPlayer(mc.player.getUUID());
        boolean valid=f!=null&&player!=null&&ControlStateMachine.isSteve()&&System.currentTimeMillis()-f.received<500;
        if(!valid||pid!=f.pid||world!=f.world||proxies.values().stream().anyMatch(e->e.level()!=player.level())) {
            proxies.values().forEach(ActorProxy::discard);proxies.clear();
        }
        if(!valid) return;
        pid=f.pid;world=f.world;
        var seen=new HashSet<Integer>();
        for(var a:f.actors) {
            if(a.uid<=0||a.guid<=0||a.nativeType==0||a.nativeType==100||a.world!=world
                ||!Float.isFinite(a.x)||!Float.isFinite(a.y)||!Float.isFinite(a.z)
                ||!Float.isFinite(a.width)||!Float.isFinite(a.height)||a.width<=0||a.width>32||a.height<=0||a.height>32
                ||(a.flags&1)==0||(a.flags&2)!=0||a.healthFrac<=0) continue;
            seen.add(a.uid);
            var proxy=proxies.get(a.uid);
            if(proxy==null||proxy.isRemoved()) {
                proxy=ActorProxy.spawn(player.level(),a.x,a.y,a.z,a.uid,a.width,a.height,a.healthFrac);
                proxies.put(a.uid,proxy);
            }
            proxy.syncNative(pid,a);
        }
        proxies.entrySet().removeIf(e->{if(!seen.contains(e.getKey())) {e.getValue().discard();return true;}return false;});
    }); }
}

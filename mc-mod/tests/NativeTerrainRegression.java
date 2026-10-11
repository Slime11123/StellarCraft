import dev.stellarcraft.world.VirtualTerrain;
import net.minecraft.world.phys.Vec3;
public class NativeTerrainRegression {
    public static void main(String[] args) {
        for(double y:new double[]{205.858306,207.0201,-0.25,0.999999}) {
            VirtualTerrain.clear();VirtualTerrain.put(-2,3,y);
            var hit=VirtualTerrain.raycast(new Vec3(-.75,y+2,1.75),new Vec3(-.75,y-2,1.75));
            if(hit==null||Math.abs(hit.getLocation().y-y)>1e-9||hit.getBlockPos().getY()!=Math.floor(y))
                throw new AssertionError("surface snapped or block address drifted: "+y);
            if(VirtualTerrain.raycast(new Vec3(-.75,y-2,1.75),new Vec3(-.75,y+2,1.75))!=null)
                throw new AssertionError("ascending ray hit ground");
            VirtualTerrain.remove(-2,3);
            if(!VirtualTerrain.isEmpty()) throw new AssertionError("stale surface retained");
        }
        System.out.println("PASS: exact native floor heights, negative grid/address, upward ray and removed cell");
    }
}

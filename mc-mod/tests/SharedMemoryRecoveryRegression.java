import dev.stellarcraft.link.Proto;
import dev.stellarcraft.link.SharedMemory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class SharedMemoryRecoveryRegression {
    public static void main(String[] args) {
        var bytes=ByteBuffer.allocateDirect((int)Proto.MAPPING_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        var memory=new SharedMemory(bytes);
        var state=new SharedMemory.McStateSnapshot();
        state.flags=Proto.McFlags.IN_WORLD|Proto.McFlags.CONTROL_STEVE;
        int[] seeds={0,1,10381,Integer.MAX_VALUE,Integer.MIN_VALUE,-1};
        for(int seed:seeds) {
            bytes.putInt((int)Proto.OFF_MC_STATE+Proto.MC_SEQ,seed);
            for(int frame=0;frame<1000;frame++) {
                state.x=frame;state.y=192;state.yaw=frame*.37f;
                memory.writeMcState(state);
                int seq=bytes.getInt((int)Proto.OFF_MC_STATE+Proto.MC_SEQ);
                if((seq&1)!=0 || bytes.getDouble((int)Proto.OFF_MC_STATE+Proto.MC_X)!=frame)
                    throw new AssertionError("Unreadable state after seed "+seed+" frame "+frame);
            }
        }
        int pid=(int)ProcessHandle.current().pid();
        if(!memory.claimMcPid(pid)||!memory.claimMcPid(pid)||memory.claimMcPid(pid+1))
            throw new AssertionError("Second process can steal live owner");
        memory.setMcPid(0);
        if(!memory.claimMcPid(pid)) throw new AssertionError("Cannot claim released owner");
        System.out.println("PASS: 6000 publications, odd/crashed seeds, sequence overflow, live owner isolation");
    }
}

import dev.stellarcraft.client.InputBridge;
import java.lang.reflect.Method;

public class ForwardedModifiersRegression {
    public static void main(String[] args) throws Exception {
        Method map=InputBridge.class.getDeclaredMethod("vkToGlfw",int.class);
        Method record=InputBridge.class.getDeclaredMethod("record",int.class,int.class,boolean.class);
        Method modifiers=InputBridge.class.getDeclaredMethod("modifiers");
        map.setAccessible(true);record.setAccessible(true);modifiers.setAccessible(true);
        for(int vk:new int[]{0xA0,0xA1,0x10,0xA2,0xA3,0xA4,0xA5}) {
            int glfw=(int)map.invoke(null,vk);
            record.invoke(null,vk,glfw,true);
            if(!InputBridge.isHeld(glfw)) throw new AssertionError("Modifier press lost");
            int expected=glfw==340||glfw==344?1:glfw==341||glfw==345?2:4;
            if((int)modifiers.invoke(null)!=expected) throw new AssertionError("Wrong mouse modifier mask");
            record.invoke(null,vk,glfw,false);
            if(InputBridge.isHeld(glfw)||(int)modifiers.invoke(null)!=0) throw new AssertionError("Modifier stuck");
        }
        record.invoke(null,0xA0,340,true);record.invoke(null,0xA1,344,true);
        record.invoke(null,0xA0,340,false);
        if((int)modifiers.invoke(null)!=1||!InputBridge.isHeld(344)) throw new AssertionError("Left release lost right Shift");
        record.invoke(null,0xA1,344,false);
        System.out.println("PASS: both Shift keys, Ctrl/Alt mapping, mouse modifier mask and independent releases");
    }
}

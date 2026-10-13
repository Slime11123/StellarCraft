import dev.stellarcraft.world.NativeJumpAttack;
import java.util.UUID;

public class NativeJumpAttackTest {
    static final UUID PLAYER=new UUID(1,2);
    static NativeJumpAttack.Motion sample(NativeJumpAttack.Motion old,long time,boolean grounded,double y,double vy) {
        return NativeJumpAttack.next(old,PLAYER,10,20,30,1,time,grounded,y,vy);
    }
    static void check(boolean value) {if(!value) throw new AssertionError();}
    public static void main(String[] args) {
        var ground=sample(null,1000,true,100,0);
        var rising=sample(ground,1050,false,100.5,.55);
        check(rising.jumped()&&!rising.descending());
        var top=sample(rising,1100,false,101,0);check(top.jumped()&&!top.descending());
        var falling=sample(top,1150,false,100.9,-.08);
        check(NativeJumpAttack.eligible(falling,PLAYER,20,1150,1,false));
        check(NativeJumpAttack.eligible(falling,PLAYER,20,1400,1,false));
        check(!NativeJumpAttack.eligible(falling,PLAYER,20,1401,1,false));
        check(!NativeJumpAttack.eligible(falling,PLAYER,20,1149,1,false));
        check(!NativeJumpAttack.eligible(falling,PLAYER,20,1150,.9f,false));
        check(!NativeJumpAttack.eligible(falling,PLAYER,20,1150,1,true));
        check(!NativeJumpAttack.eligible(falling,new UUID(3,4),20,1150,1,false));
        check(!NativeJumpAttack.eligible(falling,PLAYER,21,1150,1,false));
        check(!sample(falling,1200,true,100,0).jumped());
        check(!sample(ground,1050,false,99.9,-.08).jumped()); // Ledge drop, no rising proof.
        check(!sample(null,1050,false,99.9,-.08).jumped());
        check(!sample(top,1101,false,101,-.08).descending()); // No real height decrease.
        check(!sample(top,1401,false,100.9,-.08).jumped()); // Feedback gap.
        check(!NativeJumpAttack.next(top,PLAYER,11,20,30,1,1150,false,100.9,-.08).jumped());
        check(!NativeJumpAttack.next(top,PLAYER,10,21,30,1,1150,false,100.9,-.08).jumped());
        check(!NativeJumpAttack.next(top,PLAYER,10,20,31,1,1150,false,100.9,-.08).jumped());
        check(!NativeJumpAttack.next(top,PLAYER,10,20,30,2,1150,false,100.9,-.08).jumped());
        check(sample(top,1150,false,Double.NaN,-.08)==null);
        check(sample(top,1150,false,100.9,Double.NaN)==null);
        try {
            var field=NativeJumpAttack.class.getDeclaredField("scoped");field.setAccessible(true);
            @SuppressWarnings("unchecked") var scope=(ThreadLocal<NativeJumpAttack.Attack>)field.get(null);
            try {NativeJumpAttack.hurt(null,null,null,1);throw new AssertionError();}
            catch(NullPointerException expected) {check(scope.get()==null);}
            var outer=new NativeJumpAttack.Attack(PLAYER,new UUID(3,4),42,falling,true,"outer");scope.set(outer);
            try {NativeJumpAttack.hurt(null,null,null,1);throw new AssertionError();}
            catch(NullPointerException expected) {check(scope.get()==outer);}
            scope.remove();
        } catch(ReflectiveOperationException error) {throw new AssertionError(error);}
        System.out.println("PASS: host jump rise/apex/descent/landing/ledge; cooldown, exclusions, identities and 250ms boundaries; damage unchanged");
    }
}

package io.earthplatform.minecraft.survival.interaction.host;
import java.util.Collection;
import java.util.List;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
/** Actual original Connection write/tick-flush seam; no native mining or bootstrap claim. */
@GameTestHolder("earthplatform_connection_probe")
public final class NativeOriginalConnectionOutputGameTests {
    @GameTestGenerator public static Collection<TestFunction> tests(){
        if(!Boolean.getBoolean("earthplatform.nativeConnectionOutputProbe")||!Boolean.getBoolean("neoforge.gameTestServer"))return List.of();
        return List.of(new TestFunction("nativeConnectionOutputProbe","nativeoriginalconnectionoutputgametests.original_connection_write_tick_flush_consume",
            "earthplatform_connection_probe:native_survival_empty",100,0,true,NativeOriginalConnectionOutputGameTests::run));
    }
    private static void run(GameTestHelper helper){
        NativeOriginalConnectionOutputs owner=NativeOriginalConnectionOutputs.openProbe(helper);Throwable primary=null;
        try{
            for(int round=0;round<16;round++){
                owner.sendUnflushed(2*round);owner.sendUnflushed(2*round+1);
                owner.requireEnqueuedOnly(2*round+2);
                owner.tickOriginal();
                for(int packet=0;packet<2;packet++){owner.consumeOriginal();owner.retireConsumed();}
                owner.requireCompleted(2*round+2,round+1);
            }
            owner.sendImmediate(32);owner.consumeOriginal();owner.retireConsumed();owner.requireCompleted(33,17);
            owner.requireCounterfeitFlushRefused();
        }catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally{try{owner.finishOriginal();}catch(RuntimeException|Error cleanup){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}}
        helper.succeed();
    }
}

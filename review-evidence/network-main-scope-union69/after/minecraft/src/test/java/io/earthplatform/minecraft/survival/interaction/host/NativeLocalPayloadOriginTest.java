package io.earthplatform.minecraft.survival.interaction.host;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.invoke.MethodType;
import java.util.List;
import net.neoforged.neoforge.network.handling.MainThreadPayloadHandler;
import org.junit.jupiter.api.Test;

/** Pure exact-adjacent shape checks; no native invocation authority. */
class NativeLocalPayloadOriginTest {
    private static final String HOOK="earthplatform$payloadCallback";
    private record Frame(Class<?> owner,String method) implements StackWalker.StackFrame {
        @Override public String getClassName(){return owner.getName();}
        @Override public String getMethodName(){return method;}
        @Override public Class<?> getDeclaringClass(){return owner;}
        @Override public int getByteCodeIndex(){return 0;}
        @Override public String getFileName(){return "model";}
        @Override public int getLineNumber(){return 1;}
        @Override public boolean isNativeMethod(){return false;}
        @Override public StackTraceElement toStackTraceElement(){return new StackTraceElement(getClassName(),method,"model",1);}
        @Override public String getDescriptor(){return "()V";}
        @Override public MethodType getMethodType(){return MethodType.methodType(void.class);}
    }
    private static List<StackWalker.StackFrame> chain(String helper,String original){
        return List.of(new Frame(NativeLocalPayloadObservation.class,"site"),new Frame(NativeLocalPayloadObservation.class,"callbackEntered"),
                new Frame(MainThreadPayloadHandler.class,helper),new Frame(MainThreadPayloadHandler.class,original));
    }
    private static boolean matches(List<StackWalker.StackFrame> frames){return NativeLocalPayloadObservation.matchesFrames(frames,HOOK,"lambda$handle$0");}
    @Test void originalCallbackShape(){assertTrue(matches(chain("wrapMethod$abc000$"+HOOK,"lambda$handle$0")));}
    @Test void boundedWrappedOriginalShape(){assertTrue(matches(chain("wrapMethod$abc000$"+HOOK,"lambda$handle$0$mixinextras$wrapped$3")));}
    @Test void wrongOriginalAndHistoricalAncestorFail(){
        assertFalse(matches(chain("wrapMethod$abc000$"+HOOK,"nestedCallback")));
        var frames=new java.util.ArrayList<>(chain("wrapMethod$abc000$"+HOOK,"nestedCallback"));
        frames.add(new Frame(MainThreadPayloadHandler.class,"lambda$handle$0"));assertFalse(matches(frames));
    }
    @Test void helperSuffixMustBeExact(){assertFalse(matches(chain("wrapMethod$abc000$"+HOOK+"$different","lambda$handle$0")));}
    @Test void foreignClassAndDirectCallFail(){
        var frames=new java.util.ArrayList<>(chain("wrapMethod$abc000$"+HOOK,"lambda$handle$0"));
        frames.set(2,new Frame(NativeLocalPayloadOriginTest.class,"wrapMethod$abc000$"+HOOK));assertFalse(matches(frames));
        assertFalse(matches(frames.subList(0,3)));
    }
    @Test void extraBridgeAndMalformedWrappedSuffixFail(){
        assertFalse(matches(chain("wrapMethod$abc000$"+HOOK,HOOK+"$mixinextras$bridge$4")));
        for(String suffix:List.of("$","$-1","$word","$123456")){
            assertFalse(matches(chain("wrapMethod$abc000$"+HOOK,"lambda$handle$0$mixinextras$wrapped"+suffix)));
        }
    }
}

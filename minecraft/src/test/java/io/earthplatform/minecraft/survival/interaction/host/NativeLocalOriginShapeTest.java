package io.earthplatform.minecraft.survival.interaction.host;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.invoke.MethodType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure bounded-frame shape tests, never native invocation or endpoint-binding authority. */
class NativeLocalOriginShapeTest {
    private static final String HOOK="earthplatform$observeAcceptedConstructor";
    private static final String HELPER="wrapOperation$zom000$"+HOOK;
    private static final String BRIDGE=HELPER+"$mixinextras$bridge$4";
    private static final class Target {}
    private static final class Other {}
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
    private static StackWalker.StackFrame frame(Class<?> owner,String method){return new Frame(owner,method);}
    private static List<StackWalker.StackFrame> chain(String caller,String original){return List.of(
            frame(NativeLocalCausalObservation.class,"site"),frame(NativeLocalCausalObservation.class,"serverConstructed"),
            frame(Target.class,HELPER),frame(Target.class,caller),frame(Target.class,original));}
    private static boolean matches(List<StackWalker.StackFrame> frames){return NativeLocalCausalObservation.matchesOriginalFrames(frames,Target.class,HOOK,"initChannel");}
    @Test void directOriginalShapeStillWorks(){assertTrue(matches(chain("initChannel","outer")));}
    @Test void exactOneSugarBridgeRequiresFollowingOriginal(){assertTrue(matches(chain(BRIDGE,"initChannel")));}
    @Test void bridgeForAnotherHelperIsRefused(){assertFalse(matches(chain("other$mixinextras$bridge$4","initChannel")));}
    @Test void sameBridgeIsNotAllowedAtAnotherSite(){assertFalse(NativeLocalCausalObservation.matchesOriginalFrames(chain(BRIDGE,"initChannel"),Target.class,"earthplatform$observeRosterAdd","initChannel"));}
    @Test void wrongFollowingOriginalIsRefused(){assertFalse(matches(chain(BRIDGE,"unrelatedCallback")));}
    @Test void multipleBridgesAreRefused(){assertFalse(matches(chain(BRIDGE,BRIDGE+"$mixinextras$bridge$5")));}
    @Test void missingFollowingOriginalIsRefused(){assertFalse(matches(chain(BRIDGE,"initChannel").subList(0,4)));}
    @Test void foreignBridgeOrOriginalClassIsRefused(){
        var frames=new java.util.ArrayList<>(chain(BRIDGE,"initChannel"));frames.set(3,frame(Other.class,BRIDGE));assertFalse(matches(frames));
        frames.set(3,frame(Target.class,BRIDGE));frames.set(4,frame(Other.class,"initChannel"));assertFalse(matches(frames));
    }
    @Test void malformedOrUnboundedBridgeSuffixIsRefused(){for(String suffix:List.of("","-1","04","4extra","65536","100000"))assertFalse(matches(chain(HELPER+"$mixinextras$bridge$"+suffix,"initChannel")),suffix);}
    @Test void historicalAncestorCannotReplaceAdjacentOriginal(){assertFalse(matches(chain("nestedCallback","initChannel")));}
}

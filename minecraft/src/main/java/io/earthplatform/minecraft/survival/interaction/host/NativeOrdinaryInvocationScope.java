package io.earthplatform.minecraft.survival.interaction.host;

import java.lang.ref.WeakReference;

/** Bounded observer metadata only. Native invocation locals retain their own scopes until finally.
 * No payload, endpoint or executable capability is retained by this common scope state. */
class NativeOrdinaryInvocationScope {
    static final int LIMIT=16;
    final NativeOrdinaryInvocationScope previous;
    final int depth;
    private final WeakReference<Thread> thread=new WeakReference<>(Thread.currentThread());
    private boolean returned;
    NativeOrdinaryInvocationScope(NativeOrdinaryInvocationScope previous){
        if(!canEnter(previous))throw new IllegalStateException("Observer invocation nesting is incomplete");
        this.previous=previous;this.depth=previous==null?1:previous.depth+1;
    }
    static boolean canEnter(NativeOrdinaryInvocationScope previous){
        return previous==null||!previous.returned&&previous.depth<LIMIT&&previous.thread.get()==Thread.currentThread();
    }
    final boolean returnedFrom(NativeOrdinaryInvocationScope current){
        if(current!=this||returned||thread.get()!=Thread.currentThread())return false;
        returned=true;return true;
    }
}

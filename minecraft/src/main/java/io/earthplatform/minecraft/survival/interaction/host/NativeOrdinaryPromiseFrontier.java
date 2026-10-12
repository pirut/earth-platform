package io.earthplatform.minecraft.survival.interaction.host;

/** Scalar-only completion publication. This component cannot authenticate a native promise. */
final class NativeOrdinaryPromiseFrontier {
    private boolean registrationReturned, claimed, callbackReturned, failed;
    synchronized boolean beginCompletion(){
        if(claimed){failed=true;return false;}
        claimed=true;return true;
    }
    synchronized void registrationReturned(boolean originalReturnedNormally){
        if(registrationReturned)failed=true;
        if(!originalReturnedNormally)failed=true;
        registrationReturned=true;
    }
    /** Called only after the native-inspection helper unwinds and its added future alias is cleared. */
    synchronized void completionReturned(boolean success){
        if(!claimed||callbackReturned||!success)failed=true;
        callbackReturned=true;
    }
    synchronized boolean observedSettled(){return !failed&&registrationReturned&&claimed&&callbackReturned;}
    synchronized boolean failed(){return failed;}
}

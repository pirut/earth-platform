package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.Objects;

/** Internal one-attempt state, never a receipt verifier or execution capability. The private Plan owns it. */
final class NativeMiningDeliveryLatch<T> {
    private T accepted;
    private boolean attempted, finished;
    private Throwable failure;
    void requireHealthy() {
        if(failure instanceof RuntimeException value)throw value;
        if(failure instanceof Error value)throw value;
    }
    void poison(Throwable value) {if(failure==null)failure=Objects.requireNonNull(value);}
    void accept(T receipt) {
        requireHealthy();
        if(accepted!=null||receipt==null)throw reject("Native mining receipt association repeated or missing");
        accepted=receipt;
    }
    void begin(T receipt) {
        requireHealthy();
        if(receipt==null||accepted!=receipt||attempted)throw reject("Native mining delivery is foreign or already attempted");
        attempted=true;
    }
    void finish(T receipt) {
        requireHealthy();
        if(receipt==null||accepted!=receipt||!attempted||finished)throw reject("Native mining completion is foreign or repeated");
        finished=true;
    }
    private SurvivalRefusal reject(String message) {var result=new SurvivalRefusal(message);poison(result);return result;}
}

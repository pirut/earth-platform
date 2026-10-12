package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.server.level.ServerPlayer;

/** Narrow package-private port for the separately source-qualified native transport owner.
 * The implementation owns both accepted-action output and independent original native output.
 * True requires original resumeFlushing/promise completion AND exact bounded consumption.
 * No implementation is installed here. Enqueue receipts and empty queues are not evidence.
 */
abstract class NativeSaveOutputReadiness {
    private final NativeEffectDeliveryHost host;
    private final ServerPlayer player;
    private final Object connection;
    private final Thread thread=Thread.currentThread();
    private volatile Throwable firstFailure;
    private boolean reading;
    NativeSaveOutputReadiness(NativeEffectDeliveryHost host,ServerPlayer player){
        this.host=java.util.Objects.requireNonNull(host);this.player=java.util.Objects.requireNonNull(player);
        connection=player.connection;
    }
    final void requireBound(NativeEffectDeliveryHost host,ServerPlayer player){
        if(Thread.currentThread()!=thread||this.host!=host||this.player!=player||player.connection!=connection
                ||!player.server.isSameThread())throw refused("Native save output owner/connection changed");
        if(firstFailure!=null){var refusal=new SurvivalRefusal("Native save output owner failed");refusal.initCause(firstFailure);throw refusal;}
    }
    final boolean settled(NativeEffectDeliveryHost host,ServerPlayer player){
        requireBound(host,player);
        if(reading)throw refused("Native output readiness was reentered");
        reading=true;
        try{
            boolean settled=settledOwnedOutputs();requireBound(host,player);return settled;
        }catch(RuntimeException|Error failure){fail(failure);throw failure;}
        finally{reading=false;}
    }
    /** Pending physical flush/consumption returns false; missing/foreign/failed ownership throws.
     * The concrete transport owner must include all independent original native packet producers.
     */
    abstract boolean settledOwnedOutputs();
    /** Monotonic authentic reservation/write history, including fully drained independent flights. */
    abstract long lineageOwnedOutputs();
    final long lineage(NativeEffectDeliveryHost host,ServerPlayer player){
        requireBound(host,player);if(reading)throw refused("Native output lineage was reentered");reading=true;
        try{long lineage=lineageOwnedOutputs();requireBound(host,player);
            if(lineage<0)throw refused("Native output lineage overflowed or lost its owner");return lineage;
        }catch(RuntimeException|Error failure){fail(failure);throw failure;}finally{reading=false;}
    }
    final synchronized void fail(Throwable failure){if(firstFailure==null)firstFailure=java.util.Objects.requireNonNull(failure);}
    private SurvivalRefusal refused(String message){var failure=new SurvivalRefusal(message);fail(failure);return failure;}
}

package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;

/** One bounded native-image lease per held epoch plus the previous durable lease.
 * Cube payloads and encoded writer ownership belong to the coordinator's distinct persistence
 * ledger. Native images remain here through release, including after the writer completes.
 */
final class NativeActiveSaveBudget {
    static final int CHAIN_BYTES = 4096; // Existing action proof reserve, unchanged.
    static final int SCALARS = 8192;
    static final int ITEM_MAX = 4096;
    static final int INVENTORY_MAX = 16 * 1024;
    static final int IMAGE_SLOT = NativeBlockBreakPlan.DROP_IMAGE_SLOT_BYTES;
    static final int SCRATCH = 48 * 1024; //16KiB recaptured inventory +24KiB codec + bounded wrappers.
    private final int retainedMaximum;
    private int retained, scratch = SCRATCH;
    private boolean captured, released;
    static int reservation(int items, int cubes, int actors) {
        if(items<0||items>16||cubes<0||cubes>64||actors<0||actors>8)
            throw new SurvivalRefusal("Reusable save exceeds existing native owner profile");
        return Math.addExact(SCRATCH,Math.addExact(INVENTORY_MAX+SCALARS+1024+cubes*256+actors*64,
                items*(IMAGE_SLOT+1024)));
    }
    NativeActiveSaveBudget(int available,int items,int cubes,int actors) {
        int reservation=reservation(items,cubes,actors);
        if(available<=reservation)throw new SurvivalRefusal("ACTIVE save lacks simultaneous durable/new/scratch capacity");
        retainedMaximum=reservation-SCRATCH;retained=retainedMaximum;
    }
    int charged(){return Math.addExact(retained,scratch);}
    void requireScratch(){if(released||scratch!=SCRATCH)throw new SurvivalRefusal("ACTIVE save scratch was retired");}
    void finishCapture(int actual){
        requireScratch();if(captured||actual<1||actual>retainedMaximum)
            throw new SurvivalRefusal("ACTIVE save escaped its prepaid native image lease");
        retained=actual;captured=true;
    }
    /** Validate first; the host removes all transient aliases before this no-callback field tail. */
    void requireRetirable(){requireScratch();if(!captured)throw new SurvivalRefusal("Incomplete save ownership cannot retire");}
    void retire(){retained=0;scratch=0;released=true;}
}

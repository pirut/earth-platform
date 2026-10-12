package io.earthplatform.minecraft.survival.interaction.host;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
/** One dispatch's cumulative logical snapshot allocation budget, including temporary recaptures. */
final class NativePlayerStepBudget {
 static final int SNAPSHOT=24*1024, INVENTORY=8*1024, ACTION_COPIES=4*INVENTORY, PROJECTED_ITEM=8*1024;
 static final int ITEM_POST_SCRATCH=8*1024;
 static final int STACK_SCRATCH=io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot.MAX_BYTES;
 private boolean scratch,deliveryReserved;
 private int deliveryRemaining;
 private final int maximum; private int reserved;
 NativePlayerStepBudget(int maximum){if(maximum<1||maximum>4*1024*1024)throw new IllegalArgumentException("Bounded step snapshot budget required");this.maximum=maximum;}
 int reserve(int bytes){if(bytes<0||bytes>maximum-reserved)throw new SurvivalRefusal("Cumulative native player-step snapshot budget exhausted before capture");reserved+=bytes;return bytes;}
 void reserveScratch(){if(!scratch){reserve(STACK_SCRATCH+ITEM_POST_SCRATCH);scratch=true;}}
 void requireScratch(){if(!scratch)throw new SurvivalRefusal("Native stack capture lacks prepaid scratch allowance");}
 void reserveDelivery(int bytes){if(deliveryReserved)throw new SurvivalRefusal("Native MOVE delivery allowance reused");reserve(bytes);deliveryReserved=true;deliveryRemaining=bytes;}
 private int captureAllowance(int bytes){if(!deliveryReserved)return reserve(bytes);if(bytes>deliveryRemaining)throw new SurvivalRefusal("Native MOVE prepaid delivery snapshots exhausted before capture");deliveryRemaining-=bytes;return bytes;}
 int snapshot(){return captureAllowance(SNAPSHOT);} int inventory(){return captureAllowance(INVENTORY);}
 void requireRemaining(int bytes){if(bytes<0||bytes>maximum-reserved)throw new SurvivalRefusal("Native MOVE lacks complete postcommit snapshot headroom");}
 int used(){return reserved;}
}

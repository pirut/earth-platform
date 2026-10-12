package io.earthplatform.minecraft.survival.persistence;
import java.util.Objects;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import static io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint.SessionPhase;
/** Modelable identity-only latch. Only Bridge full pre-release validation calls mint.
 * It cannot look up durability, create native phase proof or open a native gate itself.
 */
final class NativeSessionReleasePermit {
 private final Thread owner=Thread.currentThread();
 private Object current,receipt,gate,phaseProof;
 private SessionPhase phase;
 void invalidate(){current=null;receipt=null;gate=null;phaseProof=null;phase=null;}
 void mint(Object current,Object receipt,Object gate,SessionPhase phase,Object phaseProof){
  if(Thread.currentThread()!=owner || phase!=SessionPhase.ACTIVE&&phase!=SessionPhase.RETURNED){invalidate();throw new SurvivalRefusal("Unadmitted native release phase");}
  this.current=Objects.requireNonNull(current);this.receipt=Objects.requireNonNull(receipt);this.gate=Objects.requireNonNull(gate);
  this.phase=phase;this.phaseProof=Objects.requireNonNull(phaseProof);
 }
 void consume(Object current,Object receipt,Object gate,SessionPhase phase,Object phaseProof){
  boolean exact=Thread.currentThread()==owner && this.current!=null && this.current==current && this.receipt==receipt
    && this.gate==gate && this.phase==phase && this.phaseProof==phaseProof;
  invalidate();if(!exact)throw new SurvivalRefusal("Missing, stale, foreign or consumed native release grant");
 }
}

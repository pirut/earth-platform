package io.earthplatform.minecraft.survival.persistence;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint.SessionPhase.*;
/** Model identity-latch tests only; do not establish native current/save/settling proof. */
class NativeSessionReleasePermitTest {
 @Test void directReleaseBeforeProofRefuses(){var permit=new NativeSessionReleasePermit();assertThrows(RuntimeException.class,()->permit.consume(new Object(),new Object(),new Object(),ACTIVE,new Object()));}
 @Test void subsequentProtocolOperationInvalidatesGrant(){var permit=new NativeSessionReleasePermit();Object c=new Object(),r=new Object(),g=new Object(),p=new Object();
  permit.mint(c,r,g,ACTIVE,p);permit.invalidate();assertThrows(RuntimeException.class,()->permit.consume(c,r,g,ACTIVE,p));}
 @Test void successIsOneShot(){var permit=new NativeSessionReleasePermit();Object c=new Object(),r=new Object(),g=new Object(),p=new Object();
  permit.mint(c,r,g,RETURNED,p);permit.consume(c,r,g,RETURNED,p);assertThrows(RuntimeException.class,()->permit.consume(c,r,g,RETURNED,p));}
 @Test void foreignCurrentReceiptOrPhaseProofCannotOpen(){Object c=new Object(),r=new Object(),g=new Object(),p=new Object();
  for(int changed=0;changed<5;changed++){var permit=new NativeSessionReleasePermit();permit.mint(c,r,g,RETURNED,p);final int which=changed;
   assertThrows(RuntimeException.class,()->permit.consume(which==0?new Object():c,which==1?new Object():r,which==2?new Object():g,which==3?ACTIVE:RETURNED,which==4?new Object():p));
   assertThrows(RuntimeException.class,()->permit.consume(c,r,g,RETURNED,p));}}
}

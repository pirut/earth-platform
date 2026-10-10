package io.earthplatform.minecraft.coregate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CoreInputLatchTest {
 @Test void onePressCannotRepeatWhileHeld(){var x=new CoreInputLatch(false,false);assertTrue(x.claim(true));for(int i=0;i<1000;i++){x.observe(false,true);assertFalse(x.claim(true));}x.observe(false,false);assertTrue(x.claim(true));}
 @Test void attackAndUseHaveSeparateReleaseEdges(){var x=new CoreInputLatch(false,false);assertTrue(x.claim(false));assertTrue(x.claim(true));x.observe(false,true);assertTrue(x.claim(false));assertFalse(x.claim(true));}
 @Test void ignoredNotReadyPressStaysDisarmedUntilRelease(){var x=new CoreInputLatch(false,false);assertTrue(x.claim(true));/* caller is not ready, so no packet */x.observe(false,true);assertFalse(x.claim(true));/* caller now ready */assertFalse(x.claim(true));x.observe(false,false);assertTrue(x.claim(true));}
 @Test void newSessionWithHeldKeysRequiresRelease(){var x=new CoreInputLatch(true,true);assertFalse(x.claim(true));assertFalse(x.claim(false));x.observe(false,false);assertTrue(x.claim(true));assertTrue(x.claim(false));}
 @Test void releasedAttackCannotAccidentallyRearmHeldUse(){var x=new CoreInputLatch(true,true);for(int i=0;i<20;i++)x.observe(false,true);assertFalse(x.claim(true));assertTrue(x.claim(false));}
}

package io.earthplatform.minecraft.survival.persistence;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Model scope-state tests; not a native handler, connection or gate installation test. */
class NativeSessionAckScopeTest {
 @Test void receiverMustBeExactRetainedCurrentConnectionAndPlayer(){Object receiver=new Object(),player=new Object();
  NativeSessionAckScope.requireReceiver(receiver,receiver,receiver,player,player);
  assertThrows(RuntimeException.class,()->NativeSessionAckScope.requireReceiver(new Object(),receiver,receiver,player,player));
  assertThrows(RuntimeException.class,()->NativeSessionAckScope.requireReceiver(receiver,receiver,new Object(),player,player));
  assertThrows(RuntimeException.class,()->NativeSessionAckScope.requireReceiver(receiver,receiver,receiver,new Object(),player));}
 @Test void successfulOriginalRevokesWriteScopeAndCannotReplay(){var scope=new NativeSessionAckScope();assertFalse(scope.permits());
  scope.run(()->assertTrue(scope.permits()));assertFalse(scope.permits());assertTrue(scope.succeeded());
  assertThrows(RuntimeException.class,()->scope.run(()->fail("replayed")));assertFalse(scope.permits());assertFalse(scope.succeeded());}
 @Test void exceptionalOriginalAlwaysRevokesScope(){var scope=new NativeSessionAckScope();var problem=new IllegalStateException("native ack failure");
  assertSame(problem,assertThrows(IllegalStateException.class,()->scope.run(()->{assertTrue(scope.permits());throw problem;})));
  assertFalse(scope.permits());assertFalse(scope.succeeded());assertSame(problem,scope.failure());}
 @Test void caughtReentrancyStillPoisonsOuterOriginal(){var scope=new NativeSessionAckScope();
  assertThrows(RuntimeException.class,()->scope.run(()->{assertThrows(RuntimeException.class,()->scope.run(()->{}));assertFalse(scope.permits());}));
  assertFalse(scope.permits());assertFalse(scope.succeeded());}
 @Test void postNativeIdentityFailureAlsoRevokesScope(){var scope=new NativeSessionAckScope();boolean[] nativeRan={false};
  assertThrows(IllegalStateException.class,()->scope.run(()->{nativeRan[0]=true;throw new IllegalStateException("disconnected on exit");}));
  assertTrue(nativeRan[0]);assertFalse(scope.permits());assertFalse(scope.succeeded());}
}

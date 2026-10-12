package io.earthplatform.minecraft.survival.persistence;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
/** Pure one-shot scope state; tests establish revocation, never native network correctness. */
final class NativeSessionAckScope {
 static void requireReceiver(Object receiver,Object retainedConnection,Object currentConnection,Object handlerPlayer,Object exactPlayer){
  if(receiver==null || receiver!=retainedConnection || receiver!=currentConnection || exactPlayer==null || handlerPlayer!=exactPlayer)
   throw new SurvivalRefusal("Foreign or stale original native acknowledgement receiver/player");
 }
 private final Thread owner=Thread.currentThread();
 private boolean open,succeeded;
 private Throwable failure;
 void run(Runnable original){
  if(Thread.currentThread()!=owner || open || succeeded || failure!=null){
   var refused=new SurvivalRefusal("Native acknowledgement scope is foreign, nested, failed or consumed");
   if(failure==null)failure=refused;throw refused;
  }
  open=true;
  try{original.run();if(failure!=null)throw new SurvivalRefusal("Native acknowledgement reentrancy poisoned the original operation");succeeded=true;}
  catch(RuntimeException | Error cause){if(failure==null)failure=cause;throw cause;}
  finally{open=false;}
 }
 boolean permits(){return Thread.currentThread()==owner && open && failure==null;}
 boolean succeeded(){return Thread.currentThread()==owner && succeeded && !open && failure==null;}
 Throwable failure(){return failure;}
}

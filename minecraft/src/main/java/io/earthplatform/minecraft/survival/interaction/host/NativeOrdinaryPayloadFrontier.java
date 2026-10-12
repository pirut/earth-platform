package io.earthplatform.minecraft.survival.interaction.host;

import java.lang.ref.WeakReference;

/** Finite weak/scalar observation of original MAIN-payload callbacks, never a physical output owner.
 * No native callback, future, listener, payload, endpoint or Throwable is retained strongly.
 * Exact source adapters preserve every original invocation, including after this observer fails.
 */
final class NativeOrdinaryPayloadFrontier {
    enum Failure { NONE, CAPACITY, IDENTITY, REUSED_TRIPLE, DUPLICATE, MISSING, ENQUEUE, CALLBACK, SOURCE }
    private final Ticket[] tickets;
    private int count;
    private volatile Failure first=Failure.NONE;
    NativeOrdinaryPayloadFrontier(int capacity){
        if(capacity<1||capacity>8192)throw new IllegalArgumentException("Finite payload inventory required");
        tickets=new Ticket[capacity];
    }
    static final class Ticket {
        final NativeOrdinaryPayloadFrontier owner;
        final int sequence;
        final WeakReference<Object> wrapper,payload,context;
        WeakReference<Object> runnable,future;
        boolean enqueueEntered,enqueueReturned,callbackEntered,callbackReturned,sourceReturned;
        Ticket(NativeOrdinaryPayloadFrontier owner,int sequence,Object wrapper,Object payload,Object context){
            this.owner=owner;this.sequence=sequence;this.wrapper=new WeakReference<>(wrapper);
            this.payload=new WeakReference<>(payload);this.context=new WeakReference<>(context);
        }
    }
    synchronized Ticket beforeSource(Object wrapper,Object payload,Object context){
        if(first!=Failure.NONE)return null;
        if(wrapper==null||payload==null||context==null){fail(Failure.IDENTITY);return null;}
        for(int i=0;i<count;i++){
            Ticket t=tickets[i];
            if(matches(t,wrapper,payload,context)){
                // Private original lambda captures only this triple. No stale invocation can be
                // distinguished from a new admission if an identical historical triple is reused.
                fail(Failure.REUSED_TRIPLE);return null;
            }
            if(!complete(t)&&missing(t)){fail(Failure.MISSING);return null;}
        }
        if(count==tickets.length){fail(Failure.CAPACITY);return null;}
        Ticket t=new Ticket(this,count+1,wrapper,payload,context);tickets[count++]=t;return t;
    }
    synchronized void beforeEnqueue(Ticket t,Object context,Object runnable){
        if(!valid(t))return;
        if(context==null||t.context.get()!=context||runnable==null||t.enqueueEntered||t.sourceReturned){fail(Failure.IDENTITY);return;}
        t.runnable=new WeakReference<>(runnable);t.enqueueEntered=true;
    }
    synchronized void enqueueReturned(Ticket t,Object future,boolean threw){
        if(!valid(t))return;
        if(!t.enqueueEntered||t.enqueueReturned){fail(Failure.IDENTITY);return;}
        t.enqueueReturned=true;
        if(threw||future==null){fail(Failure.ENQUEUE);return;}
        t.future=new WeakReference<>(future); // Identity only: no virtual future polling/listener.
    }
    synchronized boolean hasTriple(Object wrapper,Object payload,Object context){
        for(int i=0;i<count;i++)if(matches(tickets[i],wrapper,payload,context))return true;return false;
    }
    synchronized Ticket callbackEntered(Object wrapper,Object payload,Object context){
        if(wrapper==null||payload==null||context==null){fail(Failure.IDENTITY);return null;}
        Ticket found=null;
        for(int i=0;i<count;i++){
            Ticket t=tickets[i];
            if(matches(t,wrapper,payload,context)){
                if(found!=null){fail(Failure.REUSED_TRIPLE);return null;}found=t;
            }else if(!complete(t)&&missing(t))fail(Failure.MISSING);
        }
        if(found==null){fail(Failure.MISSING);return null;}
        if(found.callbackEntered){fail(Failure.DUPLICATE);return null;}
        if(!found.enqueueEntered){fail(Failure.IDENTITY);return null;}
        found.callbackEntered=true;return found;
    }
    synchronized void callbackReturned(Ticket t,boolean threw){
        if(!valid(t))return;
        if(!t.callbackEntered||t.callbackReturned){fail(Failure.IDENTITY);return;}
        t.callbackReturned=true;if(threw)fail(Failure.CALLBACK);
    }
    synchronized void sourceReturned(Ticket t,boolean threw){
        if(!valid(t))return;
        if(t.sourceReturned){fail(Failure.DUPLICATE);return;}
        t.sourceReturned=true;if(threw)fail(Failure.SOURCE);
    }
    synchronized void fail(Failure reason){if(first==Failure.NONE)first=reason;}
    synchronized boolean originalBodiesReturned(){
        if(first!=Failure.NONE)return false;
        for(int i=0;i<count;i++)if(!complete(tickets[i]))return false;
        return true;
    }
    synchronized int pending(){int n=0;for(int i=0;i<count;i++)if(!complete(tickets[i]))n++;return n;}
    synchronized long lineage(){return count;}
    Failure failure(){return first;}
    private boolean valid(Ticket t){
        if(t==null)return false;
        if(t.owner!=this||t.sequence<1||t.sequence>count||tickets[t.sequence-1]!=t){fail(Failure.IDENTITY);return false;}
        return true;
    }
    private static boolean matches(Ticket t,Object wrapper,Object payload,Object context){
        return t.wrapper.get()==wrapper&&t.payload.get()==payload&&t.context.get()==context;
    }
    private static boolean missing(Ticket t){return t.wrapper.get()==null||t.payload.get()==null||t.context.get()==null;}
    private static boolean complete(Ticket t){return t.sourceReturned&&t.enqueueReturned&&t.callbackReturned;}
}

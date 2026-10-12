package io.earthplatform.minecraft.survival.interaction.host;

import java.lang.ref.WeakReference;

/** Prepaid ordinary-baseline milestones. No callback, promise, channel or Throwable is retained strongly.
 * Native source adapters observe the unchanged original invocation; this is not an Earth payload owner.
 */
final class NativeOrdinaryRootFrontier {
    enum Kind { QUEUED_CONSUMER, INLINE_CONSUMER, INBOUND_CONFIGURATION, OUTBOUND_CONFIGURATION }
    enum Failure { NONE, CAPACITY, IDENTITY, AMBIGUOUS, DUPLICATE, PUBLICATION, CALLBACK, RELEASE, PROMISE, SOURCE, LISTENERS }
    private final Ticket[] tickets;
    private int count;
    private volatile Failure failure = Failure.NONE;

    NativeOrdinaryRootFrontier(int capacity) {
        if (capacity < 1 || capacity > 640) throw new IllegalArgumentException("Finite root inventory required");
        tickets = new Ticket[capacity];
    }
    static final class Ticket {
        final NativeOrdinaryRootFrontier owner;
        final int sequence;
        final Kind kind;
        final WeakReference<Object> receiver, payload, channel;
        WeakReference<Object> promise;
        boolean publicationReturned, callbackEntered, callbackReturned, sourceReturned;
        boolean handlerEntered, handlerReturned, releaseReturned, promiseReturned;
        Ticket(NativeOrdinaryRootFrontier owner, int sequence, Kind kind, Object receiver, Object payload, Object channel) {
            this.owner=owner;this.sequence=sequence;this.kind=kind;
            this.receiver=new WeakReference<>(receiver);this.payload=new WeakReference<>(payload);this.channel=new WeakReference<>(channel);
        }
        boolean configuration() { return kind==Kind.INBOUND_CONFIGURATION || kind==Kind.OUTBOUND_CONFIGURATION; }
    }
    synchronized Ticket beforePublication(Kind kind, Object receiver, Object payload, Object channel) {
        if (failure!=Failure.NONE) return null;
        if (kind==null || receiver==null || payload==null || (isConfiguration(kind)&&channel==null)) { fail(Failure.IDENTITY);return null; }
        for(int i=0;i<count;i++) {
            Ticket existing=tickets[i];
            if(!complete(existing) && existing.receiver.get()==receiver && existing.payload.get()==payload) {
                fail(Failure.AMBIGUOUS);return null;
            }
        }
        if(count==tickets.length){fail(Failure.CAPACITY);return null;}
        Ticket result=new Ticket(this,count+1,kind,receiver,payload,channel);tickets[count++]=result;return result;
    }
    /** Lookup never treats a missing/collected callback as a completed ticket, or guesses queue order. */
    synchronized Ticket find(Object receiver,Object payload) {
        Ticket found=null;
        for(int i=0;i<count;i++) {
            Ticket candidate=tickets[i];
            if(!complete(candidate)&&candidate.receiver.get()==receiver&&candidate.payload.get()==payload) {
                if(found!=null){fail(Failure.AMBIGUOUS);return null;}found=candidate;
            }
        }
        if(found==null)fail(Failure.IDENTITY);return found;
    }
    synchronized Ticket findConfiguration(Object channel,Object payload) {
        Ticket found=null;
        for(int i=0;i<count;i++) {
            Ticket candidate=tickets[i];
            if(candidate.configuration()&&!complete(candidate)&&candidate.channel.get()==channel&&candidate.payload.get()==payload) {
                if(found!=null){fail(Failure.AMBIGUOUS);return null;}found=candidate;
            }
        }
        if(found==null)fail(Failure.IDENTITY);return found;
    }
    synchronized void queuedPublicationReturned(Ticket ticket, boolean accepted, boolean threw) {
        if(!valid(ticket))return;
        if(ticket.kind!=Kind.QUEUED_CONSUMER || ticket.publicationReturned){fail(Failure.IDENTITY);return;}
        ticket.publicationReturned=true;if(!accepted||threw)fail(Failure.PUBLICATION);
    }
    synchronized void callbackEntered(Ticket ticket,Object receiver,Object payload) {
        if(!valid(ticket))return;
        if(receiver==null||payload==null||ticket.receiver.get()!=receiver||ticket.payload.get()!=payload){fail(Failure.IDENTITY);return;}
        if(ticket.callbackEntered){fail(Failure.DUPLICATE);return;}
        if(ticket.configuration()&&!ticket.handlerEntered){fail(Failure.IDENTITY);return;}
        ticket.callbackEntered=true;
    }
    synchronized void callbackReturned(Ticket ticket, boolean threw) {
        if(!valid(ticket))return;
        if(!ticket.callbackEntered||ticket.callbackReturned){fail(Failure.IDENTITY);return;}
        ticket.callbackReturned=true;if(threw)fail(Failure.CALLBACK);
    }
    synchronized void sourceReturned(Ticket ticket,boolean threw) {
        if(!valid(ticket))return;
        if(ticket.sourceReturned){fail(Failure.DUPLICATE);return;}
        ticket.sourceReturned=true;if(threw)fail(Failure.SOURCE);
    }
    synchronized void handlerEntered(Ticket ticket,Object channel,Object payload,Object promise) {
        if(!valid(ticket))return;
        if(!ticket.configuration()||ticket.handlerEntered||ticket.channel.get()!=channel||ticket.payload.get()!=payload||promise==null){fail(Failure.IDENTITY);return;}
        if(ticket.promise!=null&&ticket.promise.get()!=promise){fail(Failure.PROMISE);return;}
        if(ticket.promise==null)ticket.promise=new WeakReference<>(promise);
        ticket.handlerEntered=true;
    }
    synchronized void configPublicationReturned(Ticket ticket,Object future,boolean threw) {
        if(!valid(ticket))return;
        if(!ticket.configuration()||ticket.publicationReturned){fail(Failure.IDENTITY);return;}
        ticket.publicationReturned=true;
        if(threw||future==null){fail(Failure.PUBLICATION);return;}
        if(ticket.promise!=null&&ticket.promise.get()!=future){fail(Failure.PROMISE);return;}
        if(ticket.promise==null)ticket.promise=new WeakReference<>(future);
    }
    synchronized void releaseReturned(Ticket ticket,Object payload,boolean referenceReleased,boolean threw) {
        if(!valid(ticket))return;
        if(!ticket.configuration()||!ticket.callbackReturned||ticket.releaseReturned||ticket.payload.get()!=payload){fail(Failure.IDENTITY);return;}
        ticket.releaseReturned=true;
        // Exact original configuration factories produce interface-only lambdas, not ReferenceCounted objects.
        if(referenceReleased||threw)fail(Failure.RELEASE);
    }
    synchronized void promiseReturned(Ticket ticket,Object promise,Object returned,boolean listenerShapeQualified,boolean threw) {
        if(!valid(ticket))return;
        if(!ticket.configuration()||!ticket.releaseReturned||ticket.promiseReturned||ticket.promise==null||ticket.promise.get()!=promise||returned!=promise){fail(Failure.PROMISE);return;}
        ticket.promiseReturned=true;if(!listenerShapeQualified)fail(Failure.LISTENERS);if(threw)fail(Failure.PROMISE);
    }
    synchronized void handlerReturned(Ticket ticket,boolean threw) {
        if(!valid(ticket))return;
        if(!ticket.configuration()||!ticket.handlerEntered||ticket.handlerReturned){fail(Failure.IDENTITY);return;}
        ticket.handlerReturned=true;if(threw)fail(Failure.CALLBACK);
    }
    synchronized void fail(Failure reason) { if(failure==Failure.NONE)failure=reason; }
    synchronized boolean observedInvocationsReturned() {
        if(failure!=Failure.NONE)return false;
        for(int i=0;i<count;i++)if(!complete(tickets[i]))return false;return true;
    }
    synchronized int pending() { int result=0;for(int i=0;i<count;i++)if(!complete(tickets[i]))result++;return result; }
    synchronized long lineage(){return count;}
    Failure failure(){return failure;}
    private boolean valid(Ticket ticket){
        if(ticket==null)return false;
        if(ticket.owner!=this||ticket.sequence<1||ticket.sequence>count||tickets[ticket.sequence-1]!=ticket){fail(Failure.IDENTITY);return false;}
        return true;
    }
    private static boolean isConfiguration(Kind kind){return kind==Kind.INBOUND_CONFIGURATION||kind==Kind.OUTBOUND_CONFIGURATION;}
    private static boolean complete(Ticket t){
        if(!t.callbackReturned||!t.sourceReturned)return false;
        if(t.kind==Kind.INLINE_CONSUMER)return true;
        if(!t.publicationReturned)return false;
        return !t.configuration() || t.handlerReturned&&t.releaseReturned&&t.promiseReturned;
    }
}

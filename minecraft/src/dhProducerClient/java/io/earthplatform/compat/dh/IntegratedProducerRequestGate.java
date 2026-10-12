package io.earthplatform.compat.dh;

import java.util.Objects;

/** Fixture-local one-shot lifecycle; grants no production render or game capability. */
final class IntegratedProducerRequestGate<S,W> {
    enum State { NEW, PENDING, RUNNING, PASS, FAIL }
    static final class Request<S,W> {
        private final S server; private final W witness; private final long generation, startedNanos;
        private Request(S server,W witness,long generation,long startedNanos){this.server=server;this.witness=witness;this.generation=generation;this.startedNanos=startedNanos;}
        S server(){return server;} W witness(){return witness;} long generation(){return generation;} long startedNanos(){return startedNanos;}
    }
    private State state=State.NEW; private long generation; private Request<S,W> request;
    synchronized State state(){return state;}
    synchronized Request<S,W> request(){return request;}
    synchronized Request<S,W> publish(S server,W witness,long now){
        if(state!=State.NEW)throw new IllegalStateException("One-shot producer request already used");
        Objects.requireNonNull(server);Objects.requireNonNull(witness);
        request=new Request<>(server,witness,Math.incrementExact(generation),now);
        generation=request.generation;state=State.PENDING;return request;
    }
    synchronized Request<S,W> consume(S exactServer){
        if(state!=State.PENDING || request.server!=exactServer)return null;
        state=State.RUNNING;return request;
    }
    synchronized void requireCurrent(Request<S,W> exact,W witness){
        if(exact==null || request!=exact || state!=State.RUNNING || generation!=exact.generation || exact.witness!=witness)
            throw new IllegalStateException("Integrated producer request retired");
    }
    synchronized boolean revoke(){
        if(state!=State.PENDING && state!=State.RUNNING)return false;
        generation=Math.incrementExact(generation);state=State.FAIL;return true;
    }
    synchronized void complete(Request<S,W> exact,Runnable publishReport){
        requireCurrent(exact,exact==null?null:exact.witness);
        try { publishReport.run(); requireCurrent(exact,exact==null?null:exact.witness); state=State.PASS; }
        catch(Throwable failure){state=State.FAIL;throw failure;}
    }
    synchronized void fail(){if(state!=State.PASS)state=State.FAIL;}
}

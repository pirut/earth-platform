package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Dedicated versioned eight-key journal. Caller owns the same exclusive store lock as native cube saves. */
final class ArenaRevisionLedger {
    private final ArenaDefinition definition;
    private final String scope,layout;
    private final ArenaReplacementIndex index;
    private ArenaReplacementIndex.Snapshot snapshot;
    private ArenaReplacementIndex.Completion completion;
    private List<ArenaReplacementIndex.Entry> entries=List.of();
    private final long[] revisions=new long[8];
    private ArenaQueryAttestation attestation;
    private CubePos pending; private long pendingRevision; private boolean poisoned;
    private ArenaRevisionLedger(ArenaDefinition definition,String scope,String layout,ArenaReplacementIndex index)throws IOException{
        this.definition=definition;this.scope=scope;this.layout=layout;this.index=index;snapshot=index.snapshot();if(index.isInitialized())accept(scan(snapshot));
    }
    static ArenaRevisionLedger open(Path directory,ArenaDefinition definition,String scope,String layout)throws IOException{return new ArenaRevisionLedger(definition,scope,layout,ArenaReplacementIndex.open(directory,scope));}
    String storeIdentity(){return snapshot.storeIdentity();} String indexIdentity(){return snapshot.indexIdentity();}
    synchronized ArenaQueryAttestation attestation(){return !poisoned&&pending==null&&index.issuedCompletionCurrent(completion)?attestation:null;}
    synchronized boolean queryCurrent(ArenaQueryAttestation value){return value!=null&&value.equals(attestation());}
    synchronized void verifyObserved(CubePos position,long revision)throws IOException{check();if(pending!=null)throw new IOException("Unresolved arena intent");int slot=definition.positions().indexOf(position);long expected=slot<0?0:revisions[slot];if(revision!=expected)throw new IOException("Arena durable revision mismatch at "+position);}
    synchronized void sealCapture(ArenaCapture capture)throws IOException{
        check();var reconstructed=entries(capture,null,-1);if(!index.isInitialized()){index.initializeSummaries(snapshot,reconstructed);snapshot=index.snapshot();}
        Scan checked=scan(snapshot);if(!checked.entries.equals(reconstructed))throw new IOException("Reconstructed arena union differs from durable summary");accept(checked);
    }
    synchronized void verifyCapture(ArenaCapture capture,List<RollingCubeActor.CubeVersion> dependencies)throws IOException{
        check();if(attestation()==null)throw new IOException("Complete current arena catalogue required");
        for(var v:dependencies)verifyObserved(v.position(),v.revision());
        if(!entries.equals(entries(capture,null,-1)))throw new IOException("Arena capture differs from durable summary");
    }
    synchronized void begin(CubePos changed,long expected,ArenaCapture prospective)throws IOException{
        check();int slot=definition.positions().indexOf(changed);if(slot<0||pending!=null||revisions[slot]!=expected)throw new IOException("Foreign/stale arena transaction");
        long next;try{next=Math.incrementExact(expected);}catch(ArithmeticException exhausted){throw new IOException("Arena revision exhausted",exhausted);}
        Scan checked=scan(snapshot);if(!entries.equals(checked.entries))throw new IOException("Arena index changed before save");
        try{index.begin(snapshot,entries(prospective,changed,next));pending=changed;pendingRevision=next;}catch(IOException|RuntimeException failure){poisoned=!index.isCurrent(snapshot);throw failure;}
    }
    synchronized void confirm(CubePos position,long revision)throws IOException{
        check();if(!position.equals(pending)||revision!=pendingRevision)throw new IOException("Arena save does not match pending intent");
        try{index.confirm();accept(scan(index.snapshot()));if(revisions[definition.positions().indexOf(position)]!=revision)throw new IOException("Arena index revision differs from cube save");pending=null;pendingRevision=0;}catch(IOException|RuntimeException failure){poisoned=true;throw failure;}
    }
    private List<ArenaReplacementIndex.Entry> entries(ArenaCapture capture,CubePos changed,long changedRevision)throws IOException{
        if(capture==null||!definition.identity().equals(capture.definition().identity()))throw new IOException("Exact arena capture required");
        var result=new ArrayList<ArenaReplacementIndex.Entry>(8);for(int i=0;i<8;i++){var p=definition.positions().get(i);result.add(new ArenaReplacementIndex.Entry(p,p.equals(changed)?changedRevision:revisions[i],scope,capture.contentIdentity()));}return List.copyOf(result);
    }
    private record Scan(ArenaReplacementIndex.Completion completion,List<ArenaReplacementIndex.Entry> entries){}
    private Scan scan(ArenaReplacementIndex.Snapshot expected)throws IOException{
        if(!index.isCurrent(expected))throw new IOException("Stale arena index");var token=index.beginQuery(ArenaReplacementIndex.Bounds.all(),8);var result=new ArrayList<ArenaReplacementIndex.Entry>(8);
        for(;;){var page=index.next(token);if(page.status()==ArenaReplacementIndex.Status.REFUSED)throw new IOException("Arena catalogue query refused: "+page.reason());if(result.size()+page.entries().size()>8)throw new IOException("Ninth arena key refused");result.addAll(page.entries());
            if(page.status()==ArenaReplacementIndex.Status.COMPLETE){var done=Objects.requireNonNull(page.completion());if(!done.snapshot().equals(expected)||!index.isCurrent(expected)||done.resultCount()!=8||!result.stream().map(ArenaReplacementIndex.Entry::position).toList().equals(definition.positions())||result.stream().map(ArenaReplacementIndex.Entry::summaryIdentity).distinct().count()!=1||result.stream().anyMatch(e->!scope.equals(e.sourcePolicyIdentity()))||!done.resultIdentity().equals(ArenaReplacementIndex.resultIdentity(result)))throw new IOException("Incomplete or foreign eight-key arena union");return new Scan(done,List.copyOf(result));}token=page.token();}
    }
    private void accept(Scan scan){completion=scan.completion;snapshot=completion.snapshot();entries=scan.entries;for(int i=0;i<8;i++)revisions[i]=entries.get(i).revision();attestation=new ArenaQueryAttestation(snapshot.storeIdentity(),snapshot.indexIdentity(),snapshot.generation(),snapshot.rootIdentity(),definition.identity(),layout,scope,completion.resultIdentity(),8);}
    private void check(){if(poisoned)throw new IllegalStateException("Arena journal durability uncertain");}
}

package io.earthplatform.minecraft.survival.runtime;

import java.lang.reflect.Field;
import net.neoforged.bus.SubscribeEventListener;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;

/** Late-only bridge. It is never referenced by the early seed's field types or code.
 * Joins actual operands with the existing bounded historical native copy, without calling
 * a Consumer, predicate, listener factory, effective-cache builder or bus mutator. */
final class InstalledConsumerOriginStartupJoin {
    private InstalledConsumerOriginStartupJoin() {}
    static void freezeFromMarker() {
        var owner=InstalledConsumerOrigins.observed();
        if(owner==null){InstalledConsumerOrigins.poison();return;}
        try {
            owner.freezeStage="FREEZE_ORIGIN";
            var frames=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                    .walk(s->s.skip(1).limit(1).toList());
            if(frames.size()!=1||frames.getFirst().getDeclaringClass()!=InstalledCallbackStartupMarker.class
                    ||!frames.getFirst().getMethodName().equals("observe")||InstalledCallbackStartupMarker.invalid()
                    ||Thread.currentThread()!=owner.seedThread||!owner.published)throw new IllegalStateException();
            if(!owner.phase.compareAndSet(0,1))throw new IllegalStateException();
            var census=InstalledCallbackStartupCensus.observed();owner.census=census;
            owner.freezeStage="JOINED_SOURCE_CUT";
            if(census==null||!census.complete||InstalledCallbackStartupCensus.repeated()
                    ||InstalledConsumerOrigins.invalid()||owner.overflow)throw new IllegalStateException();
            // SourceCut has already qualified the exact installed bus and producer originals.
            // Rebind actual receipt Classes and independently retain localized hash evidence.
            Class<?> earth=type(InstalledConsumerOrigins.hostName(0));
            Class<?> neo=type(InstalledConsumerOrigins.hostName(4));
            String earthHash=pin(owner,earth,InstalledConsumerOrigins.hostPin(0));
            String neoHash=pin(owner,neo,InstalledConsumerOrigins.hostPin(4));
            pin(owner,type("net.neoforged.neoforge.capabilities.CapabilityHooks"),
                    "9df3a2d0792e09e5cddad9e0dcc478b129605da0b1816b8f85533a9179fbf54b");
            Class<?> consumerType=type("net.neoforged.bus.ConsumerEventHandler");
            Class<?> predicateType=type("net.neoforged.bus.ConsumerEventHandler$WithPredicate");
            pin(owner,consumerType,"aa624b2d0714835fc2258bf0fad66c4b83e15916d13f0e85d308cd6e3efce68d");
            pin(owner,predicateType,"238c2d7d75aaac9aefaadebed46a2a7b16044ee81ab32e7f0a81bdeaae6a1da5");
            Field consumerField=field(consumerType,"consumer"),predicateField=field(predicateType,"predicate");
            owner.consumerField=consumerField;owner.predicateField=predicateField;
            owner.freezeStage="EIGHT_NORMAL_RETURNS";
            for(int site=0;site<InstalledConsumerOrigins.SITES;site++){
                var receipt=owner.receipts[site];
                if(owner.states.get(site)!=2||!receipt.beforeQualified||receipt.nativeFailure!=null
                        ||receipt.bus!=NeoForge.EVENT_BUS||receipt.hostClass!=(site<4?earth:neo)
                        ||receipt.host==null||receipt.host.getClass()!=receipt.hostClass)throw new IllegalStateException();
                receipt.expectedHost=InstalledConsumerOrigins.hostPin(site);receipt.actualHost=site<4?earthHash:neoHash;
                receipt.event=type(InstalledConsumerOrigins.eventName(site));
                for(int other=0;other<site;other++)if(owner.receipts[other].consumer==receipt.consumer)throw new IllegalStateException();
                for(int i=0;i<census.registryCount;i++)if(census.registry[i].event()==receipt.event){
                    if(receipt.list!=null)throw new IllegalStateException();receipt.list=census.registry[i].list();
                }
                if(receipt.list==null)throw new IllegalStateException();
            }
            owner.freezeStage="NATIVE_IDENTITY_JOIN";
            // Copy lists are already unique native identities. Inherited report paths do not
            // cause repeated copies or a second binding of the same underlying registration.
            for(int i=0;i<census.listCount;i++){
                var list=census.lists[i];
                for(int phase=0;phase<list.priorities().length;phase++)for(Object wrapper:list.priorities()[phase]){
                    if(wrapper==null)throw new IllegalStateException();
                    if(wrapper.getClass()==SubscribeEventListener.class)continue;
                    if(wrapper.getClass()!=consumerType&&wrapper.getClass()!=predicateType){
                        owner.conflictWrapper=wrapper;throw new IllegalStateException();
                    }
                    Object consumer=consumerField.get(wrapper);int matched=-1;
                    for(int site=0;site<InstalledConsumerOrigins.SITES;site++)if(owner.receipts[site].consumer==consumer){matched=site;break;}
                    if(matched<0){owner.otherConsumerReferences++;continue;} // Explicitly counted, not qualified.
                    var receipt=owner.receipts[matched];
                    if(receipt.wrapper!=null||wrapper.getClass()!=predicateType||receipt.list!=list.list()
                            ||phase!=EventPriority.NORMAL.ordinal()){
                        owner.conflictWrapper=wrapper;throw new IllegalStateException();
                    }
                    receipt.wrapper=wrapper;receipt.predicate=predicateField.get(wrapper);
                    if(receipt.predicate==null)throw new IllegalStateException();
                    owner.boundCount++;
                }
            }
            if(owner.boundCount!=InstalledConsumerOrigins.SITES||InstalledConsumerOrigins.invalid())throw new IllegalStateException();
            owner.freezeStage="EIGHT_HISTORICAL_ORIGINS_BOUND_NO_LIFETIME_APPROVAL";owner.complete=true;
        }catch(Throwable failure){
            InstalledConsumerOrigins.poison();owner.freezeFailure=failure.getClass();
        }finally{owner.frozen=true;} // Volatile publication also retains every partial join/failure.
    }
    static InstalledConsumerOrigins.Receipt bound(Object wrapper,Object consumer,Object predicate,Object list,EventPriority phase){
        var owner=InstalledConsumerOrigins.observed();
        if(owner==null||!owner.frozen||!owner.complete||InstalledConsumerOrigins.invalid())return null;
        for(int site=0;site<InstalledConsumerOrigins.SITES;site++){
            var receipt=owner.receipts[site];
            if(receipt.wrapper==wrapper&&receipt.consumer==consumer&&receipt.predicate==predicate&&receipt.list==list&&phase==EventPriority.NORMAL)return receipt;
        }
        return null;
    }
    private static Class<?> type(String name)throws ClassNotFoundException {
        return Class.forName(name,false,InstalledConsumerOriginStartupJoin.class.getClassLoader());
    }
    private static Field field(Class<?> owner,String name)throws ReflectiveOperationException {
        Field result=owner.getDeclaredField(name);if(!result.trySetAccessible())throw new IllegalStateException();return result;
    }
    private static String pin(InstalledConsumerOrigins.Owner owner,Class<?> type,String expected)throws Exception {
        owner.freezeStage="CONSUMER_SOURCE_PIN";owner.pinKey=InstalledConsumerOrigins.clip(type.getName());
        owner.pinExpected=expected;owner.pinActual="CHECKING";owner.pinActual=InstalledConsumerOrigins.hash(type);
        if(!owner.pinActual.equals(expected))throw new IllegalStateException();return owner.pinActual;
    }
}

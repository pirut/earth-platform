package io.earthplatform.minecraft.survival.runtime;

import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.Consumer;

/** Separate observation ledger. No native registrar/event Class reference may enter this
 * preseed class: it is initialized before FML loads those transform targets. No Approval. */
public final class InstalledConsumerOrigins {
    static final int SITES=8, RECEIPTS=17;
    private static final AtomicInteger SEEDS=new AtomicInteger();
    private static volatile Owner retained;
    private static volatile boolean invalid;
    private static volatile Class<?> reservationFailure,linkageFailure;
    private InstalledConsumerOrigins() {}
    static final class Receipt {
        final Owner owner; final int index,site;
        Object host,bus; Consumer<?> consumer; Thread thread;
        Class<?> hostClass,directClass,callerClass,nativeFailure,observerFailure;
        String stage="UNCLAIMED",directMethod="",callerDescriptor="";
        String beforeStage="NOT_ENTERED",afterStage="NOT_ENTERED";
        int beforeFrameCount,afterFrameCount;
        boolean beforeQualified,nativeReturned;
        String expectedHost="",actualHost="";
        Object wrapper,list,predicate; Class<?> event;
        Receipt(Owner owner,int index,int site){this.owner=owner;this.index=index;this.site=site;}
    }
    static final class Owner {
        NativeDistanceCpuBudget.Reservation charge;
        Object minecraft; Thread seedThread; Class<?> minecraftClass,seedFailure,seedDirectOwner,seedCallerOwner;
        int seedFrameCount;String seedDirectMethod="",seedCallerMethod="",seedCallerDescriptor="";
        String seedStage="ALLOCATED",expectedMinecraft="495f5dc97d07476e23835fcdd034019a020a58b980f7c764d4953ce4ed16d283",actualMinecraft="";
        AtomicIntegerArray states; AtomicInteger phase; Receipt[] receipts;
        volatile boolean published,overflow,frozen,complete;
        String freezeStage="NOT_FROZEN",pinKey="",pinExpected="",pinActual="";
        Class<?> freezeFailure;int boundCount,otherConsumerReferences;Object census,consumerField,predicateField,conflictWrapper;
        void initialize(){
            states=new AtomicIntegerArray(RECEIPTS);phase=new AtomicInteger();receipts=new Receipt[RECEIPTS];
            for(int i=0;i<RECEIPTS;i++)receipts[i]=new Receipt(this,i,i<16?i%SITES:-1);
        }
    }
    /** Real Minecraft constructor helper passes its own isSameThread result. Stack/source
     * authentication prevents that scalar from standing alone as an origin claim. */
    public static void seed(Object minecraft,boolean ownerThread){
        if(SEEDS.incrementAndGet()!=1){invalid=true;return;}
        Owner owner=null;
        try{
            var charge=NativeDistanceCpuBudget.global().reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,256L<<10);
            owner=new Owner();retained=owner;owner.charge=charge;charge.claimRetainedOwner(owner);
            owner.initialize();owner.seedStage="SEED_ORIGIN";
            if(!Boolean.getBoolean("earthplatform.installedSessionProbe")||Boolean.getBoolean("neoforge.gameTestServer")
                    ||minecraft==null||!ownerThread)throw new IllegalStateException();
            owner.minecraft=minecraft;owner.seedThread=Thread.currentThread();owner.minecraftClass=minecraft.getClass();
            if(!owner.minecraftClass.getName().equals("net.minecraft.client.Minecraft")
                    ||owner.minecraftClass.getClassLoader()!=InstalledConsumerOrigins.class.getClassLoader())throw new IllegalStateException();
            owner.seedStage="SEED_FRAMES";
            var frames=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(s->s.skip(1).limit(2).toList());
            owner.seedFrameCount=frames.size();
            if(!frames.isEmpty())owner.seedDirectOwner=frames.get(0).getDeclaringClass();
            if(frames.size()>1)owner.seedCallerOwner=frames.get(1).getDeclaringClass();
            if(owner.seedDirectOwner==owner.minecraftClass)owner.seedDirectMethod=clip(frames.get(0).getMethodName());
            if(owner.seedCallerOwner==owner.minecraftClass){
                owner.seedCallerMethod=clip(frames.get(1).getMethodName());owner.seedCallerDescriptor=clip(frames.get(1).getDescriptor());
            }
            if(frames.size()!=2||frames.get(0).getDeclaringClass()!=owner.minecraftClass
                    ||!frames.get(0).getMethodName().endsWith("$earthplatform$installedConsumerSeed")
                    ||frames.get(1).getDeclaringClass()!=owner.minecraftClass||!frames.get(1).getMethodName().equals("<init>")
                    ||!frames.get(1).getDescriptor().equals("(Lnet/minecraft/client/main/GameConfig;)V"))throw new IllegalStateException();
            owner.seedStage="SEED_PIN";owner.actualMinecraft=hash(owner.minecraftClass);
            if(!owner.actualMinecraft.equals(owner.expectedMinecraft))throw new IllegalStateException();
            owner.seedStage="SEEDED_BEFORE_FML_BEGIN";owner.published=true;
        }catch(Throwable failure){invalid=true;if(owner!=null)owner.seedFailure=failure.getClass();else reservationFailure=failure.getClass();}
    }
    /** Observation cannot prevent the original registration. Every helper is a no-throw
     * boundary; mixins additionally cover initialization/linkage failure at the callsite. */
    public static Object before(int site,Object host,Object bus,Consumer<?> consumer,boolean originalGlobalBus){
        Receipt receipt=null;
        try{
            Owner owner=retained;
            if(owner==null||!owner.published){invalid=true;return null;}
            int index=site>=0&&site<SITES?site:16;
            if(!owner.states.compareAndSet(index,0,1)){
                invalid=true;index=site>=0&&site<SITES?site+SITES:16;
                if(!owner.states.compareAndSet(index,0,1)){owner.overflow=true;return null;}
            }
            receipt=owner.receipts[index];receipt.stage="ARGUMENTS_RETAINED";receipt.beforeStage="ARGUMENTS_RETAINED";
            receipt.host=host;receipt.bus=bus;receipt.consumer=consumer;receipt.thread=Thread.currentThread();
            if(index>=SITES||owner.phase.get()!=0){invalid=true;receipt.stage="DUPLICATE_UNKNOWN_OR_LATE";return receipt;}
            receipt.beforeStage="HOST_BUS_LINEAGE";receipt.hostClass=host==null?null:host.getClass();
            if(!originalGlobalBus||bus==null||consumer==null||receipt.hostClass==null
                    ||!receipt.hostClass.getName().equals(hostName(site))
                    ||receipt.hostClass.getClassLoader()!=InstalledConsumerOrigins.class.getClassLoader())throw new IllegalStateException();
            receipt.beforeStage="CALLSITE_FRAMES";
            var frames=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(s->s.skip(1).limit(2).toList());
            receipt.beforeFrameCount=frames.size();if(frames.size()!=2)throw new IllegalStateException();
            receipt.directClass=frames.get(0).getDeclaringClass();receipt.callerClass=frames.get(1).getDeclaringClass();
            if(receipt.directClass==receipt.hostClass)receipt.directMethod=clip(frames.get(0).getMethodName());
            if(receipt.callerClass==receipt.hostClass)receipt.callerDescriptor=clip(frames.get(1).getDescriptor());
            receipt.beforeStage="CALLSITE_IDENTITY";
            if(receipt.directClass!=receipt.hostClass||!receipt.directMethod.endsWith("$"+helperName(site))
                    ||receipt.callerClass!=receipt.hostClass||!frames.get(1).getMethodName().equals("<init>")
                    ||!receipt.callerDescriptor.equals(constructorDescriptor(site)))throw new IllegalStateException();
            receipt.beforeQualified=true;receipt.beforeStage="AUTHENTICATED";receipt.stage="BEFORE_ORIGINAL";return receipt;
        }catch(Throwable failure){invalid=true;if(receipt!=null){receipt.observerFailure=failure.getClass();receipt.stage="BEFORE_REFUSED";}return receipt;}
    }
    public static void after(Object value,boolean returned,Class<?> nativeFailure){
        Receipt receipt=null;
        try{
            if(value==null){invalid=true;return;}
            if(value.getClass()!=Receipt.class){invalid=true;return;}
            receipt=(Receipt)value;Owner owner=retained;
            receipt.nativeFailure=nativeFailure;receipt.nativeReturned=returned;receipt.afterStage="OWNER_THREAD_PHASE";
            if(owner!=receipt.owner||Thread.currentThread()!=receipt.thread||owner.phase.get()!=0)throw new IllegalStateException();
            if(receipt.site<0||receipt.index>=SITES||!receipt.beforeQualified){invalid=true;receipt.stage="UNQUALIFIED_ORIGINAL_RETURN";owner.states.set(receipt.index,returned?2:3);return;}
            receipt.afterStage="CALLSITE_FRAMES";
            var frames=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(s->s.skip(1).limit(2).toList());
            receipt.afterFrameCount=frames.size();
            if(frames.size()!=2||frames.get(0).getDeclaringClass()!=receipt.hostClass
                    ||!frames.get(0).getMethodName().endsWith("$"+helperName(receipt.site))
                    ||frames.get(1).getDeclaringClass()!=receipt.hostClass||!frames.get(1).getMethodName().equals("<init>")
                    ||!frames.get(1).getDescriptor().equals(constructorDescriptor(receipt.site)))throw new IllegalStateException();
            receipt.afterStage="AUTHENTICATED";receipt.stage=returned?"ORIGINAL_RETURNED":"ORIGINAL_FAILED";
            if(!returned||nativeFailure!=null)invalid=true;
            if(!owner.states.compareAndSet(receipt.index,1,returned?2:3))throw new IllegalStateException();
        }catch(Throwable failure){invalid=true;if(receipt!=null){receipt.observerFailure=failure.getClass();receipt.stage="AFTER_REFUSED";}}
    }
    public static void observerFailed(Class<?> failure){invalid=true;linkageFailure=failure;}
    static Class<?> linkageFailure(){return linkageFailure;}
    static Owner observed(){return retained;}
    static boolean invalid(){return invalid;}
    static Class<?> reservationFailure(){return reservationFailure;}
    static void poison(){invalid=true;}
    static String hostName(int site){return site<4?"io.earthplatform.minecraft.EarthPlatformMod":"net.neoforged.neoforge.common.NeoForgeMod";}
    static String hostPin(int site){return site<4?"c867a3049ea72e690f879d2018e6c5cb359414f56bb3404a9fa0cb2f4e8555ef":"98dc057e7bc44f959c9b8587e4f978230385a31ac20e8aa73a6991b279f874c4";}
    static String constructorDescriptor(int site){return site<4?"(Lnet/neoforged/bus/api/IEventBus;)V":"(Lnet/neoforged/bus/api/IEventBus;Lnet/neoforged/api/distmarker/Dist;Lnet/neoforged/fml/ModContainer;)V";}
    static String helperName(int site){return switch(site){
        case 0->"earthplatform$consumerEarthCommands";case 1->"earthplatform$consumerEarthDimension";
        case 2->"earthplatform$consumerEarthLogin";case 3->"earthplatform$consumerEarthPreflight";
        case 4->"earthplatform$consumerNeoStopping";case 5->"earthplatform$consumerCapabilityLoad";
        case 6->"earthplatform$consumerCapabilityUnload";case 7->"earthplatform$consumerCapabilityPost";
        default->throw new IllegalArgumentException();};}
    static String eventName(int site){return switch(site){
        case 0->"net.neoforged.neoforge.event.RegisterCommandsEvent";
        case 1->"net.neoforged.neoforge.event.entity.player.PlayerEvent$PlayerChangedDimensionEvent";
        case 2->"net.neoforged.neoforge.event.entity.player.PlayerEvent$PlayerLoggedInEvent";
        case 3->"net.neoforged.neoforge.event.server.ServerAboutToStartEvent";
        case 4->"net.neoforged.neoforge.event.server.ServerStoppingEvent";
        case 5->"net.neoforged.neoforge.event.level.ChunkEvent$Load";
        case 6->"net.neoforged.neoforge.event.level.ChunkEvent$Unload";
        case 7->"net.neoforged.neoforge.event.tick.LevelTickEvent$Post";
        default->throw new IllegalArgumentException();};}
    static String sourceImplementation(int site){return switch(site){
        case 0->"EarthPlatformMod.registerCommands";case 1->"EarthPlatformMod.playerChangedDimension";
        case 2->"EarthPlatformMod.playerLoggedIn";case 3->"EarthPlatformMod.serverPreflight";
        case 4->"NeoForgeMod.serverStopping";case 5->"CapabilityHooks.invalidateCapsOnChunkLoad";
        case 6->"CapabilityHooks.invalidateCapsOnChunkUnload";case 7->"CapabilityHooks.cleanCapabilityListenerReferencesOnTick";
        default->throw new IllegalArgumentException();};}
    static int ordinal(int site){return site<4?site+2:switch(site){case 4->6;case 5->11;case 6->12;case 7->13;default->throw new IllegalArgumentException();};}
    static int offset(int site){return switch(site){case 0->41;case 1->54;case 2->67;case 3->80;case 4->228;case 5->395;case 6->408;case 7->421;default->throw new IllegalArgumentException();};}
    static String clip(String s){if(s.length()>192)throw new IllegalStateException();return s;}
    static String hash(Class<?> type) throws Exception {
        try(var in=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){
            if(in==null)throw new IllegalStateException();var digest=MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[8192];int total=0;
            for(int read;(read=in.read(buffer))!=-1;){total=Math.addExact(total,read);if(total>512*1024)throw new IllegalStateException();digest.update(buffer,0,read);}
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}

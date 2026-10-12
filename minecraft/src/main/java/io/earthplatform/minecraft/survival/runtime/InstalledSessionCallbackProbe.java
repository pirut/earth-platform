package io.earthplatform.minecraft.survival.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.EventBus;
import net.neoforged.bus.ListenerList;
import net.neoforged.bus.SubscribeEventListener;
import net.neoforged.bus.GeneratedEventListener;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventListener;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;

/** Bounded installed diagnostic. Never an admission capability.
 * No event/consumer/receiver invocation, synthetic registration, native cache construction,
 * queue mutation, toString on foreign objects, or unbounded lock wait.
 * Only the authentic, source-closed startup census supplies callback registry metadata.
 * Missing/failed startup evidence refuses; no later native backing/cache fallback exists.
 * Even a complete report says nothing about the downstream DH/native effect closure.
 */
public final class InstalledSessionCallbackProbe {
    private static final int MAX_EVENTS = 128, MAX_MAP = 512, MAX_LISTENERS = 64, MAX_DEPTH = 16;
    private static final int MAX_METADATA_BYTES=1024*1024;
    private static final int MAX_REPORT_BYTES=1024*1024, MAX_LINE_CHARS=8192, MAX_ORIGIN_CHARS=1024;
    private static final String[] EVENT_NAMES = {
            "net.neoforged.neoforge.event.entity.player.PlayerEvent$LoadFromFile",
            "net.neoforged.neoforge.event.entity.player.PlayerEvent$SaveToFile",
            "net.neoforged.neoforge.event.entity.player.PlayerEvent$NameFormat",
            "net.neoforged.neoforge.event.entity.player.PlayerEvent$TabListNameFormat",
            "net.neoforged.neoforge.event.entity.player.PlayerEvent$PlayerChangeGameModeEvent",
            "net.neoforged.neoforge.event.entity.player.PlayerEvent$PlayerLoggedInEvent",
            "net.neoforged.neoforge.event.entity.player.PlayerEvent$PlayerLoggedOutEvent",
            "net.neoforged.neoforge.event.entity.player.PlayerEvent$PlayerChangedDimensionEvent",
            "net.neoforged.neoforge.event.entity.player.PlayerEvent$StartTracking",
            "net.neoforged.neoforge.event.entity.player.PlayerEvent$StopTracking",
            "net.neoforged.neoforge.event.tick.ServerTickEvent$Pre",
            "net.neoforged.neoforge.event.tick.ServerTickEvent$Post",
            "net.neoforged.neoforge.event.tick.LevelTickEvent$Pre",
            "net.neoforged.neoforge.event.tick.LevelTickEvent$Post",
            "net.neoforged.neoforge.event.tick.EntityTickEvent$Pre",
            "net.neoforged.neoforge.event.tick.EntityTickEvent$Post",
            "net.neoforged.neoforge.event.tick.PlayerTickEvent$Pre",
            "net.neoforged.neoforge.event.tick.PlayerTickEvent$Post",
            "net.neoforged.neoforge.event.level.LevelEvent$Load",
            "net.neoforged.neoforge.event.level.LevelEvent$Save",
            "net.neoforged.neoforge.event.level.LevelEvent$Unload",
            "net.neoforged.neoforge.event.level.ChunkEvent$Load",
            "net.neoforged.neoforge.event.level.ChunkEvent$Unload",
            "net.neoforged.neoforge.event.level.ChunkDataEvent$Save",
            "net.neoforged.neoforge.event.entity.player.PlayerInteractEvent$LeftClickBlock",
            "net.neoforged.neoforge.event.entity.player.PlayerInteractEvent$RightClickBlock",
            "net.neoforged.neoforge.event.level.BlockEvent$BreakEvent",
            "net.neoforged.neoforge.event.level.BlockEvent$EntityPlaceEvent",
            "net.neoforged.neoforge.event.level.BlockEvent$EntityMultiPlaceEvent",
            "net.neoforged.neoforge.event.level.BlockEvent$NeighborNotifyEvent",
            "net.neoforged.neoforge.event.level.ChunkWatchEvent$Watch",
            "net.neoforged.neoforge.event.level.ChunkWatchEvent$UnWatch",
            "net.neoforged.neoforge.event.level.ChunkWatchEvent$Sent",
            "net.neoforged.neoforge.event.level.ChunkTicketLevelUpdatedEvent",
            "net.neoforged.neoforge.event.entity.EntityEvent$EnteringSection",
            "net.neoforged.neoforge.event.entity.EntityEvent$Size",
            "net.neoforged.neoforge.event.entity.EntityEvent$EntityConstructing",
            "net.neoforged.neoforge.event.entity.EntityJoinLevelEvent",
            "net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent",
            "net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent",
            "net.neoforged.neoforge.event.entity.player.AdvancementEvent$AdvancementEarnEvent",
            "net.neoforged.neoforge.event.entity.player.AdvancementEvent$AdvancementProgressEvent",
            "net.neoforged.neoforge.event.StatAwardEvent",
            "net.neoforged.neoforge.event.PlayLevelSoundEvent$AtPosition",
            "net.neoforged.neoforge.event.VanillaGameEvent",
            "net.neoforged.neoforge.event.OnDatapackSyncEvent",
            "net.neoforged.neoforge.event.RegisterCommandsEvent",
            "net.neoforged.neoforge.event.server.ServerAboutToStartEvent",
            "net.neoforged.neoforge.event.server.ServerStoppingEvent"
    };
    private int reportBytes;
    // Conservative allocation accounting, separate from report text; no credit is recycled.
    private final InstalledProbeMetadataBudget metadata = new InstalledProbeMetadataBudget(MAX_METADATA_BYTES);
    private String stage="OWNER";
    private static final Map<String, String> BUS_PINS = Map.ofEntries(
            Map.entry("net.neoforged.bus.EventBus", "cda8876c904d1374f77bbff02e8cdb01fba48ae0f528c69713eaefa11b5128a4"),
            Map.entry("net.neoforged.bus.ListenerList", "3daccbcb1ef602dff0edfbbd1ed5483e4633d5bed6c0a1ccb35c01cef5c83888"),
            Map.entry("net.neoforged.bus.SubscribeEventListener", "2dc270df84436f3458564e2a9542cf4a175d9b2426db70917672397998e0efd0"),
            Map.entry("net.neoforged.bus.GeneratedEventListener", "57f93a48701152d66414a84655fb7cd60cc19e83d03502daf2336240c222c4ad"),
            Map.entry("net.neoforged.bus.EventListenerFactory", "807424c87435f908d9a75b9fe52b43ab2e2925e97affb7b4e4057c8808485ed0"),
            Map.entry("net.neoforged.bus.LockHelper", "28a7dd87748b9c42334ea2a1853d43ff808b73cb70cb8ce03eb40adfe93a50bc"),
            Map.entry("net.neoforged.bus.ConsumerEventHandler", "aa624b2d0714835fc2258bf0fad66c4b83e15916d13f0e85d308cd6e3efce68d"),
            Map.entry("net.neoforged.bus.ConsumerEventHandler$WithPredicate", "238c2d7d75aaac9aefaadebed46a2a7b16044ee81ab32e7f0a81bdeaae6a1da5"));
    private final IdentityHashMap<Object, Integer> identities = new IdentityHashMap<>();
    private final ArrayList<Object> retained = new ArrayList<>();
    private final ArrayList<String> lines = new ArrayList<>();
    private final IdentityHashMap<Class<?>, Method> methodsByHandlerClass = new IdentityHashMap<>();
    private boolean complete = true;

    private InstalledSessionCallbackProbe() { metadata.reserve(4096); }
    record Report(List<String> lines, boolean completeDiagnostic) {
        public Report { lines = List.copyOf(lines); }
    }
    /** Use only from an actual IntegratedServer owner-thread diagnostic hook after real login. */
    static Report observe(ServerPlayer actualPlayer) {
        var p = new InstalledSessionCallbackProbe();
        try { p.capture(actualPlayer); }
        catch (ReflectiveOperationException | java.io.IOException | java.security.NoSuchAlgorithmException | RuntimeException ex) {
            p.complete = false;
            p.notice("NOT_OBSERVED: bounded diagnostic refused: " + describeFailure(p.stage,ex));
        }
        p.notice("DIAGNOSTIC_ONLY: no callback or installed-session Approval minted; stable observation is not mutation exclusion");
        p.notice("METADATA modeledChargedBytes="+p.metadata.charged()+" cap="+MAX_METADATA_BYTES);
        return new Report(p.lines, p.complete);
    }
    private void capture(ServerPlayer player)
            throws ReflectiveOperationException, java.io.IOException, java.security.NoSuchAlgorithmException {
        if (player == null || player.getClass() != ServerPlayer.class
                || player.server.getClass() != IntegratedServer.class || !player.server.isSameThread()
                || player.server.isPublished() || player.connection == null
                || player.server.getPlayerList().getClass()!=net.minecraft.client.server.IntegratedPlayerList.class) refuse("Exact logged-in integrated owner required");
        stage="STARTUP_ORIGIN";
        var startup=InstalledCallbackStartupMarker.snapshot();
        boolean invalidStartup=InstalledCallbackStartupMarker.invalid();
        line("STARTUP_CUT seen="+(startup!=null)+" invalid="+invalidStartup
                +" modeledBytes="+InstalledCallbackStartupMarker.modeledBytes());
        var markerTrace=InstalledCallbackStartupMarker.trace();
        var reservationFailure=InstalledCallbackStartupMarker.reservationFailureClass();
        if(markerTrace!=null)line("STARTUP_MARKER_TRACE stage="+markerTrace.stage+" key="+markerTrace.key
                +" expected="+markerTrace.expected+" actual="+markerTrace.actual+" frames="+markerTrace.frameCount
                +" directOwner="+id(markerTrace.directOwner)+" directMethod="+markerTrace.directMethod
                +" callerOwner="+id(markerTrace.callerOwner)+" callerMethod="+markerTrace.callerMethod
                +" failureType="+(markerTrace.failureClass==null?"none":markerTrace.failureClass.getName()));
        if(reservationFailure!=null)line("STARTUP_MARKER_BUDGET_FAILURE type="+reservationFailure.getName());
        if(startup==null || invalidStartup)complete=false;
        if(startup!=null){
            line("STARTUP_HOST "+classIdentity(startup.host()));
            line("STARTUP_LOADER_HOST "+classIdentity(startup.loaderHost()));
            line("STARTUP_BUS_HOST "+classIdentity(startup.busHost()));
            line("STARTUP_STATE loading="+startup.loading()+" loadingComplete="+startup.loadingComplete()
                    +" noLoadingError="+startup.noLoadingError()+" shutdown="+startup.shutdown()
                    +" production="+startup.production()+" beforeWorld="+startup.beforeWorld());
        }
        line("STARTUP_QUALIFICATION original callsite plus conditional startup snapshot; no later mutation exclusion or Approval");
        consumerOrigins();
        line("NATIVE_OBSERVATION_PROFILE "+InstalledObservationNativePins.PROFILE+" manifest="+InstalledObservationNativePins.MANIFEST_SHA256);
        nativePin(IntegratedServer.class);
        nativePin(net.minecraft.server.MinecraftServer.class);
        requireNativePlayerRoster(player);
        nativeTransport(player);
        if(Boolean.getBoolean("earthplatform.nativeLocalFrontierObservation"))
            for(String observation:io.earthplatform.minecraft.survival.interaction.host.NativeLocalCausalObservation.snapshot())line(observation);
        try { dhState(); }
        catch (ReflectiveOperationException | java.io.IOException | java.security.NoSuchAlgorithmException | RuntimeException failure) {
            complete=false; line("DH NOT_OBSERVED: " + describeFailure(stage,failure));
        }
        if (EVENT_NAMES.length > MAX_EVENTS) refuse("Fixed diagnostic event roster bound");
        for (var pin : BUS_PINS.entrySet()) pin(type(pin.getKey()), pin.getValue());
        stage="BUS_REGISTRY";
        Object bus = NeoForge.EVENT_BUS;
        if (bus.getClass() != EventBus.class) refuse("Unexpected event bus class");
        line("SERVER " + id(player.server) + " PLAYER " + id(player) + " LISTENER " + id(player.connection));
        stage="STARTUP_CENSUS";
        var startupCensus=InstalledCallbackStartupCensus.observed();
        line("STARTUP_CENSUS present="+(startupCensus!=null)+" repeated="+InstalledCallbackStartupCensus.repeated());
        var censusBudgetFailure=InstalledCallbackStartupCensus.reservationFailureClass();
        if(censusBudgetFailure!=null)line("STARTUP_CENSUS_BUDGET_FAILURE type="+censusBudgetFailure.getName());
        if(startupCensus==null)refuse("No genuine source-closed startup census");
        line("STARTUP_CENSUS_STATE complete="+startupCensus.complete+" stage="+startupCensus.stage
                +" failureType="+(startupCensus.failureClass==null?"none":startupCensus.failureClass.getName())
                +" reservedBytes="+startupCensus.reservation.bytes()+" modeledCopyBytes="+startupCensus.allocation.charged()
                +" factoryCount="+startupCensus.factoryCount+" registryCount="+startupCensus.registryCount
                +" uniqueLists="+startupCensus.listCount+" owners="+startupCensus.ownerCount
                +" callbackReferences="+startupCensus.callbackReferences);
        var sourceCheck=startupCensus.sourceCheck;
        if(sourceCheck!=null)line("STARTUP_SOURCE_CHECK kind="+sourceCheck.kind()+" key="+sourceCheck.key()
                +" expected="+sourceCheck.expected()+" actual="+sourceCheck.actual());
        if(!startupCensus.complete || InstalledCallbackStartupCensus.repeated())refuse("Startup census failed or repeated");
        for(int index=0;index<startupCensus.factoryCount;index++){
            var factory=startupCensus.factories[index];metadata.reserve(192);
            Method prior=methodsByHandlerClass.put(factory.handlerClass(),factory.method());
            if(prior!=null && prior!=factory.method())refuse("Nonunique actual native factory-class identity");
            retain(factory.method());retain(factory.constructor());retain(factory.handlerClass());
        }
        for(int index=0;index<startupCensus.ownerCount;index++){
            var owner=startupCensus.owners[index];
            line("STARTUP_OWNER "+id(owner.owner())+" nativeList="+id(owner.nativeSynchronizedList())
                    +" membership=NOT_OBSERVED_MONITOR_BACKED");
        }
        for(String eventName:EVENT_NAMES){
            Class<?> event=type(eventName);stage="STARTUP_EVENT:"+eventName;
            startupEvent(event,startupCensus);
        }
        line("STARTUP_CENSUS_QUALIFICATION original startup Method/handle/receiver/Consumer identities; no current effective-cache or later mutation-exclusion proof");
        // Historic startup identity is useful observation, but it cannot silently promote the
        // existing completeDiagnostic field to current live-lifecycle or admission authority.
        complete=false;
    }
    private void consumerOrigins() {
        var owner=InstalledConsumerOrigins.observed();
        line("CONSUMER_ORIGINS present="+(owner!=null)+" invalid="+InstalledConsumerOrigins.invalid()
                +" budgetFailure="+className(InstalledConsumerOrigins.reservationFailure())
                +" linkageFailure="+className(InstalledConsumerOrigins.linkageFailure()));
        if(owner==null)return;
        boolean frozen=owner.frozen; // Acquire the historical join or bounded partial refusal.
        line("CONSUMER_ORIGIN_SEED published="+owner.published+" stage="+owner.seedStage
                +" expected="+owner.expectedMinecraft+" actual="+owner.actualMinecraft
                +" minecraft="+id(owner.minecraft)+" thread="+id(owner.seedThread)
                +" failure="+className(owner.seedFailure)+" reservedBytes="+(owner.charge==null?0:owner.charge.bytes()));
        line("CONSUMER_ORIGIN_SEED_FRAMES count="+owner.seedFrameCount+" directClass="+id(owner.seedDirectOwner)
                +" directMethod="+owner.seedDirectMethod+" callerClass="+id(owner.seedCallerOwner)
                +" callerMethod="+owner.seedCallerMethod+" callerDescriptor="+owner.seedCallerDescriptor);
        line("CONSUMER_ORIGIN_FREEZE frozen="+frozen+" complete="+owner.complete+" overflow="+owner.overflow
                +" stage="+owner.freezeStage+" bound="+owner.boundCount+" otherConsumerReferences="+owner.otherConsumerReferences
                +" failure="+className(owner.freezeFailure)+" key="+owner.pinKey+" expected="+owner.pinExpected+" actual="+owner.pinActual
                +" conflictWrapper="+id(owner.conflictWrapper));
        if(!frozen||owner.receipts==null)return;
        for(int index=0;index<InstalledConsumerOrigins.RECEIPTS;index++){
            int state=owner.states.get(index);if(state==0)continue;
            var receipt=owner.receipts[index];
            line("CONSUMER_ORIGIN_RECEIPT slot="+index+" site="+receipt.site+" state="+state+" stage="+receipt.stage
                    +" beforeStage="+receipt.beforeStage+" beforeFrames="+receipt.beforeFrameCount
                    +" afterStage="+receipt.afterStage+" afterFrames="+receipt.afterFrameCount+" nativeReturned="+receipt.nativeReturned
                    +" qualifiedBefore="+receipt.beforeQualified+" host="+id(receipt.host)+" hostClass="+id(receipt.hostClass)
                    +" bus="+id(receipt.bus)+" consumer="+id(receipt.consumer)+" thread="+id(receipt.thread)
                    +" nativeFailure="+className(receipt.nativeFailure)+" observerFailure="+className(receipt.observerFailure));
            if(receipt.site<0)continue;
            line("CONSUMER_ORIGIN_SOURCE site="+receipt.site+" ctor="+InstalledConsumerOrigins.constructorDescriptor(receipt.site)
                    +" ordinal="+InstalledConsumerOrigins.ordinal(receipt.site)+" offset="+InstalledConsumerOrigins.offset(receipt.site)
                    +" overload=addListener(Ljava/util/function/Consumer;)V direct="+receipt.directMethod
                    +" directClass="+id(receipt.directClass)+" callerClass="+id(receipt.callerClass)
                    +" expected="+receipt.expectedHost+" actual="+receipt.actualHost);
            line("CONSUMER_ORIGIN_JOIN site="+receipt.site+" wrapper="+id(receipt.wrapper)+" list="+id(receipt.list)
                    +" predicate="+id(receipt.predicate)+" event="+id(receipt.event)
                    +" implementation="+InstalledConsumerOrigins.sourceImplementation(receipt.site)
                    +" sourceReceiver="+id(receipt.site==4?receipt.host:null)
                    +" nativeFactoryMethodKey=NOT_APPLICABLE sourcePhase=NORMAL sourceReceiveCancelled=false");
        }
        line("CONSUMER_ORIGIN_QUALIFICATION eight exact constructor operands joined to historical native wrappers; other origins and later mutation exclusion remain unproved; no Approval");
    }
    private static String className(Class<?> type){return type==null?"none":type.getName();}
    private void startupEvent(Class<?> event,InstalledCallbackStartupCensus.Capture census)throws ReflectiveOperationException {
        if(!Event.class.isAssignableFrom(event))refuse("Invalid requested startup event class");
        Object leaf=null;int steps=0;Class<?> declared=event;
        for(Class<?> ancestor=event;ancestor!=null && Event.class.isAssignableFrom(ancestor);ancestor=ancestor.getSuperclass()){
            if(++steps>MAX_DEPTH)refuse("Startup event ancestor bound");
            for(int i=0;i<census.registryCount;i++)if(census.registry[i].event()==ancestor){leaf=census.registry[i].list();declared=ancestor;break;}
            if(leaf!=null)break;
        }
        line("STARTUP_EVENT "+event.getName()+" "+classIdentity(event)+" nearestInstantiated="+(leaf==null?"none":declared.getName()));
        if(leaf==null){line("  STARTUP_DECLARED_AND_INHERITED_ROSTER empty; live effective cache not inspected");return;}
        metadata.reserve(512+16L*MAX_DEPTH);
        var chain=new ArrayList<InstalledCallbackStartupCensus.ListCopy>(MAX_DEPTH);
        for(Object node=leaf;node!=null;){
            if(chain.size()>=MAX_DEPTH)refuse("Startup native parent-chain bound");
            for(var seen:chain)if(seen.list()==node)refuse("Startup native parent cycle");
            InstalledCallbackStartupCensus.ListCopy state=null;
            for(int i=0;i<census.listCount;i++)if(census.lists[i].list()==node){state=census.lists[i];break;}
            if(state==null)refuse("Startup native parent missing from captured registry");
            chain.add(state);node=state.parent();
        }
        for(EventPriority phase:EventPriority.values()){
            int count=0;
            for(var state:chain)for(Object listener:state.priorities()[phase.ordinal()]){
                if(++count>MAX_LISTENERS)refuse("Startup inherited phase-listener bound");
                describe(listener,event,phase,state.list());
            }
        }
    }
    private void nativeTransport(ServerPlayer player)
            throws ReflectiveOperationException, java.io.IOException, java.security.NoSuchAlgorithmException {
        Class<?> listenerType=net.minecraft.server.network.ServerGamePacketListenerImpl.class;
        nativePin(listenerType);
        nativePin(net.minecraft.server.network.PlayerChunkSender.class);
        stage="NATIVE_SENDER";
        if(player.connection.getClass()!=listenerType || player.connection.player!=player)refuse("Original listener identity changed");
        Object sender=field(listenerType,player.connection,"chunkSender");
        Class<?> senderType=net.minecraft.server.network.PlayerChunkSender.class;
        if(sender==null || sender.getClass()!=senderType)refuse("Original sender identity changed");
        Object pending=field(senderType,sender,"pendingChunks");
        if(pending==null || pending.getClass()!=it.unimi.dsi.fastutil.longs.LongOpenHashSet.class)refuse("Original sender pending set class changed");
        int pendingCount=((it.unimi.dsi.fastutil.longs.LongOpenHashSet)pending).size();
        if(pendingCount<0 || pendingCount>16384)refuse("Sender diagnostic count bound");
        line("NATIVE tick="+player.server.getTickCount()+" paused="+((IntegratedServer)player.server).isPaused()
                +" effectiveView="+player.server.getPlayerList().getViewDistance()+" simulation="+player.server.getPlayerList().getSimulationDistance());
        line("SENDER "+id(sender)+" pending="+pendingCount+" memory="+field(senderType,sender,"memoryConnection")
                +" unacknowledged="+field(senderType,sender,"unacknowledgedBatches")
                +" maxUnacknowledged="+field(senderType,sender,"maxUnacknowledgedBatches")
                +" quota="+field(senderType,sender,"batchQuota")+" desired="+field(senderType,sender,"desiredChunksPerTick"));
        line("SENDER_ROOT MinecraftServer.tickChildren invokes sendNextChunks independently of held listener.tick; this probe sends/acknowledges nothing");
    }
    private void dhState() throws ReflectiveOperationException, java.io.IOException, java.security.NoSuchAlgorithmException {
        pin(type("com.seibel.distanthorizons.core.api.internal.SharedApi"),"c0e3957eb98da66249d41b490b0f6450bae41394a749afa66f2c2f81a208e18d");
        pin(type("com.seibel.distanthorizons.core.world.DhClientServerWorld"),"d0f270e914adafe4723e9ff29d602ac79f45bfb2718fc0d8022b03bf31f9f39e");
        pin(type("com.seibel.distanthorizons.core.world.AbstractDhServerWorld"),"abdd6c99850bd269db1898a49f5986aab1b136b10e264b4bfbe3c32a97c33b95");
        pin(type("com.seibel.distanthorizons.core.world.DhApiWorldProxy"),"31de668ce2f8b558bc8c3e1a57fe9de376db845bcd41323f25135034d6204c7a");
        pin(type("com.seibel.distanthorizons.common.wrappers.misc.ServerPlayerWrapper_neoforge"),"6685db5ccd0db9b8992be1aed4011da6bc697a2ecdc0bf66536ede5e71aafd01");
        pin(type("com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper_neoforge"),"64fe84ee4d54bfde005fdb0d9ca5facf3edc0162f2d0592548b5f05bf455b9d0");
        pin(type("com.seibel.distanthorizons.core.level.AbstractDhServerLevel"),"d35133a380e04cca200a5eba89ccdea634b94283bd8f0db85004875078dde872");
        pin(type("com.seibel.distanthorizons.core.level.DhClientServerLevel"),"4244b1eec2a175eb0022e7320b06708c1214de2ffeae28fd95aa759e08b68800");
        pin(type("com.seibel.distanthorizons.core.api.internal.chunkUpdating.WorldChunkUpdateManager"),"53c749214dd0e274d2f174a4cb25611691c0d941ce2fa5f2874057f18eef4beb");
        pin(type("com.seibel.distanthorizons.core.api.internal.chunkUpdating.ChunkUpdateQueueManager"),"6cc4c1d966be6c0c50add0853683170d5ec77867aeb524c5f62f33527deab5af");
        pin(type("com.seibel.distanthorizons.core.api.internal.chunkUpdating.ChunkPosQueue"),"b97905b73257ec27987ed4bdde8d15ad82dcd9048c6a9f3e41b6e3c83fe4d54b");
        pin(type("com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper_neoforge"),"729a4b027063c29a07afe8dfb36c07142bd8123b526efb83fac0ae9400f05c4e");
        stage="DH_WORLD";
        Class<?> shared=type("com.seibel.distanthorizons.core.api.internal.SharedApi");
        Object world=field(shared,null,"currentWorld");
        if(world==null || world.getClass()!=type("com.seibel.distanthorizons.core.world.DhClientServerWorld"))refuse("Actual integrated DH world absent or foreign");
        Class<?> proxyType=type("com.seibel.distanthorizons.core.world.DhApiWorldProxy"); Object proxy=field(proxyType,null,"INSTANCE");
        if(proxy==null || proxy.getClass()!=proxyType)refuse("Actual DH world proxy differs");
        line("DH_WORLD "+id(world)+" proxy="+id(proxy)+" readOnly="+field(proxyType,proxy,"isReadOnly"));
        Class<?> serverWorld=type("com.seibel.distanthorizons.core.world.AbstractDhServerWorld");
        stage="DH_LEVEL_MAP";
        var levels=concurrentMap(field(serverWorld,world,"dhLevelByLevelWrapper"),32);
        line("DH_LEVEL_MAP "+id(levels)+" approximateSize="+levels.size()+" consistency=weakly-consistent");
        Class<?> serverWrapperType=type("com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper_neoforge");
        Class<?> clientWrapperType=type("com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper_neoforge");
        Class<?> levelType=type("com.seibel.distanthorizons.core.level.DhClientServerLevel");
        Class<?> base=type("com.seibel.distanthorizons.core.level.AbstractDhServerLevel");
        int levelCount=0,serverKeys=0,clientKeys=0;
        for(var entry:levels.entrySet()){
            if(++levelCount>32)refuse("Concurrent DH level iteration bound");
            Object wrapper=entry.getKey(),level=entry.getValue();
            // Record the actual objects before applying the fixed domain predicate. The
            // earlier observation003 did not record the type of its refused entry.
            line("DH_LEVEL_ENTRY index="+levelCount+" wrapper="+id(wrapper)+" value="+id(level));
            if(wrapper==null || level==null || level.getClass()!=levelType)
                refuse("Actual DH map key/value differs; see preceding raw entry");
            if(wrapper.getClass()!=serverWrapperType && wrapper.getClass()!=clientWrapperType)
                refuse("Unpinned DH map key class; see preceding raw entry");
            boolean serverKey=wrapper.getClass()==serverWrapperType;
            Class<?> wrapperType=serverKey?serverWrapperType:clientWrapperType;
            Object nativeLevel=field(wrapperType,wrapper,"level");
            Object wrapperDhLevel=field(wrapperType,wrapper,"dhLevel");
            Object serverWrapper=field(base,level,"serverLevelWrapper");
            if(serverWrapper==null || serverWrapper.getClass()!=serverWrapperType)
                refuse("DH level has no exact server wrapper");
            if(serverKey && serverWrapper!=wrapper)refuse("DH server-key identity differs");
            if(wrapperDhLevel!=level)refuse("DH wrapper link differs or changed concurrently");
            if(serverKey)serverKeys++;else clientKeys++;
            // Both key kinds can refer to the same DhClientServerLevel. Never treat the
            // number of map keys as the number of independently owned DH levels.
            line("DH_LEVEL kind="+(serverKey?"server":"client-alias")+" wrapper="+id(wrapper)
                    +" native="+id(nativeLevel)+" level="+id(level)+" wrapperDhLevel="+id(wrapperDhLevel)
                    +" serverWrapper="+id(serverWrapper));
            if(serverKey){
                Object queue=field(base,level,"worldGenPlayerCenteringQueue");
                line("DH_CENTER_QUEUE_ENTRY level="+id(level)+" queue="+id(queue));
                if(queue==null || queue.getClass()!=java.util.concurrent.ConcurrentLinkedQueue.class)
                    refuse("Actual DH player center queue differs");
                // Even JDK ConcurrentLinkedQueue iteration can advance head/unlink dead nodes.
                // Keep this native owner opaque; no iterator/size/isEmpty/peek/toArray fallback.
                complete=false;
                line("DH_CENTER_QUEUE level="+id(level)+" queue="+id(queue)
                        +" membership=NOT_OBSERVED cleanupCapableReadsNotInvoked=true");
            }
        }
        line("DH_LEVEL_KEY_COUNTS observed="+levelCount+" server="+serverKeys+" clientAliases="+clientKeys
                +" consistency=weakly-consistent noExclusivity=true");
        stage="DH_PLAYER_CACHE";
        Class<?> playerWrapperType=type("com.seibel.distanthorizons.common.wrappers.misc.ServerPlayerWrapper_neoforge");
        Object wrappers=field(playerWrapperType,null,"serverPlayerWrapperMap");
        // Pinned DH uses MapMaker.weakKeys().weakValues().makeMap(). Its normal reads can
        // create views/drain reference queues. Observe actual identity before any classification;
        // do not guess the runtime implementation or prevent independent queue-owner reporting.
        complete=false;
        line("DH_PLAYER_CACHE owner="+id(wrappers)+" origin="
                +(wrappers==null?"unavailable":classIdentity(wrappers.getClass()))
                +" membership=NOT_OBSERVED_WEAK_CACHE consistency=concurrent-and-gc-sensitive");
        stage="DH_CHUNK_QUEUES";
        Class<?> managerType=type("com.seibel.distanthorizons.core.api.internal.chunkUpdating.WorldChunkUpdateManager");
        Object manager=field(shared,null,"WORLD_CHUNK_UPDATE_MANAGER");
        line("DH_CHUNK_UPDATE_MANAGER owner="+id(manager));
        if(manager==null || manager.getClass()!=managerType)refuse("DH update manager differs; see preceding raw identity");
        var queues=concurrentMap(field(managerType,manager,"updateQueueByLevelWrapper"),32);int queueCount=0;
        Class<?> queueType=type("com.seibel.distanthorizons.core.api.internal.chunkUpdating.ChunkUpdateQueueManager");
        Class<?> positionQueue=type("com.seibel.distanthorizons.core.api.internal.chunkUpdating.ChunkPosQueue");
        for(var entry:queues.entrySet()){
            if(++queueCount>32)refuse("DH update queue iteration bound");Object queue=entry.getValue();
            line("DH_CHUNK_QUEUE_ENTRY levelWrapper="+id(entry.getKey())+" value="+id(queue));
            if(queue==null || queue.getClass()!=queueType)refuse("DH actual update queue differs; see preceding raw entry");
            line("DH_CHUNK_QUEUE levelWrapper="+id(entry.getKey())+" manager="+id(queue)+" executor="+id(field(queueType,queue,"queueingThread"))+" neighborCache="+id(field(queueType,queue,"queuedChunkWrapperByChunkPos"))+" maxSize="+field(queueType,queue,"maxSize")
                    +" executorState=NOT_OBSERVED neighborCacheMembership=NOT_OBSERVED");
            for(String name:new String[]{"preUpdateQueue","updateQueue"}){
                Object q=field(queueType,queue,name);if(q==null || q.getClass()!=positionQueue)refuse("DH position queue differs");
                var map=concurrentMap(field(positionQueue,q,"updateDataByChunkPos"),4096);
                line("DH_POSITION_QUEUE kind="+name+" owner="+id(q)+" map="+id(map)+" approximateSize="+map.size()
                        +" closest="+id(field(positionQueue,q,"closestQueue"))
                        +" furthest="+id(field(positionQueue,q,"furthestQueue"))
                        +" center="+id(field(positionQueue,q,"center"))
                        +" priorityMembership=NOT_OBSERVED consistency=weakly-consistent");
            }
        }
        line("DH_NOT_CLOSED: raw concurrent census does not establish mutation exclusion, in-flight tasks, generation policy, neighbor/cache ownership, SQL completion, or swallowed-error exclusion");
    }
    private static java.util.concurrent.ConcurrentHashMap<?,?> concurrentMap(Object value,int limit){
        if(value==null || value.getClass()!=java.util.concurrent.ConcurrentHashMap.class)refuse("Unexpected DH concurrent backing class");
        var map=(java.util.concurrent.ConcurrentHashMap<?,?>)value;if(map.size()>limit)refuse("DH raw map diagnostic bound");return map;
    }
    private void describe(Object listener, Class<?> event, EventPriority phase, Object source)
            throws ReflectiveOperationException {
        String prefix = "  " + phase.name() + " list=" + id(source) + " wrapper=" + id(listener);
        if (listener.getClass() == SubscribeEventListener.class) {
            Object handler = field(SubscribeEventListener.class, listener, "handler");
            if (handler == null) refuse("Null native subscriber handler");
            Method method = methodsByHandlerClass.get(handler.getClass());
            if (method == null) { complete = false; line(prefix + " UNWITNESSED native method factory"); return; }
            Object receiver = null;
            metadata.reserve(1024); // Pinned makeClass emits zero fields or one instance field.
            Field[] fields = handler.getClass().getDeclaredFields();
            if (Modifier.isStatic(method.getModifiers())) { if (fields.length != 0) refuse("Static handler has fields"); }
            else {
                if (fields.length != 1 || !fields[0].getName().equals("instance") || fields[0].getType() != Object.class)
                    refuse("Instance handler layout changed");
                receiver = field(handler.getClass(), handler, "instance");
                if (receiver == null || !method.getDeclaringClass().isInstance(receiver)) refuse("Subscriber receiver mismatch");
            }
            if (method.getParameterCount() != 1 || !method.getParameterTypes()[0].isAssignableFrom(event))
                refuse("Subscriber effective event does not match actual Method");
            var annotation = method.getAnnotation(net.neoforged.bus.api.SubscribeEvent.class);
            if (annotation == null || annotation.priority() != phase) refuse("Subscriber annotation priority mismatch");
            Object actualAnnotation = field(SubscribeEventListener.class, listener, "subInfo");
            if (actualAnnotation != annotation) { complete = false; line(prefix + " UNWITNESSED annotation identity actual="
                    + id(actualAnnotation) + " method=" + id(annotation)); }
            line(prefix + " handler=" + id(handler) + " method=" + method.getDeclaringClass().getName() + "#"
                    + method.getName() + MethodType.methodType(method.getReturnType(), method.getParameterTypes()).descriptorString()
                    + " receiver=" + id(receiver) + " subscription=" + id(actualAnnotation) + " methodAnnotationReceiveCanceled=" + annotation.receiveCanceled()
                    + " declaration=" + classIdentity(method.getDeclaringClass()));
            startupOwnerAssociation(listener,receiver==null?method.getDeclaringClass():receiver);
        } else if (listener.getClass() == type("net.neoforged.bus.ConsumerEventHandler$WithPredicate")
                || listener.getClass() == type("net.neoforged.bus.ConsumerEventHandler")) {
            Object consumer = field(type("net.neoforged.bus.ConsumerEventHandler"), listener, "consumer");
            Object predicate = listener.getClass() == type("net.neoforged.bus.ConsumerEventHandler$WithPredicate")
                    ? field(listener.getClass(), listener, "predicate") : null;
            complete = false;
            var origin=InstalledConsumerOriginStartupJoin.bound(listener,consumer,predicate,source,phase);
            if(origin==null)line(prefix + " UNWITNESSED consumer=" + id(consumer) + " predicate=" + id(predicate));
            else line(prefix + " HISTORICAL_CONSTRUCTOR_ORIGIN site="+origin.site+" consumer="+id(consumer)
                    +" predicate="+id(predicate)+" registrar="+id(origin.host)
                    +" implementation="+InstalledConsumerOrigins.sourceImplementation(origin.site)
                    +" sourceReceiver="+id(origin.site==4?origin.host:null)+" currentMembership=NOT_PROVEN");
            startupOwnerAssociation(listener,consumer);
        } else { complete = false; line(prefix + " UNWITNESSED wrapper class"); }
    }
    private void startupOwnerAssociation(Object wrapper,Object derivedOwner) {
        var census=InstalledCallbackStartupCensus.observed();boolean keyPresent=false;
        for(int i=0;i<census.ownerCount;i++)if(census.owners[i].owner()==derivedOwner){keyPresent=true;break;}
        line("  STARTUP_SOURCE_DERIVED_OWNER wrapper="+id(wrapper)+" candidate="+id(derivedOwner)
                +" ownerMapKeyIdentityPresent="+keyPresent+" nativeOwnerListMembership=NOT_OBSERVED");
    }
    private void requireNativePlayerRoster(ServerPlayer player)
            throws ReflectiveOperationException, java.io.IOException, java.security.NoSuchAlgorithmException {
        nativePin(net.minecraft.server.level.ServerPlayer.class);
        nativePin(net.minecraft.server.players.PlayerList.class);
        nativePin(net.minecraft.client.server.IntegratedPlayerList.class);
        stage="PLAYER_ROSTER";
        Object list=player.server.getPlayerList();
        Object rawPlayers=field(net.minecraft.server.players.PlayerList.class,list,"players");
        Object rawById=field(net.minecraft.server.players.PlayerList.class,list,"playersByUUID");
        if (rawPlayers==null || rawPlayers.getClass()!=ArrayList.class || rawById==null || rawById.getClass()!=HashMap.class)
            refuse("Native player roster backing classes differ before any collection operation");
        var players=(ArrayList<?>)rawPlayers; var byId=(HashMap<?,?>)rawById;
        if (players.size()!=1 || byId.size()!=1 || players.get(0)!=player || byId.get(player.getUUID())!=player)
            refuse("Exact original logged-in player not present in both native raw rosters");
        retain(list); retain(rawPlayers); retain(rawById);
    }
    private void line(String text) {
        if (text==null || text.length()>MAX_LINE_CHARS) refuse("Diagnostic per-line bound");
        int charged=Math.addExact(64,Math.multiplyExact(text.length(),2));
        if ((long)reportBytes+charged>MAX_REPORT_BYTES-4096 || lines.size()>=4094) refuse("Diagnostic aggregate retained report bound");
        reportBytes+=charged; lines.add(text);
    }
    private void notice(String text) {
        // Fixed internal terminal notices use a separately reserved bounded tail.
        if (text.length()>1024 || (long)reportBytes+64+2L*text.length()>MAX_REPORT_BYTES || lines.size()>=4096)
            throw new IllegalStateException("Fixed diagnostic terminal allowance exceeded");
        reportBytes+=64+2*text.length(); lines.add(text);
    }
    private static String origin(java.net.URL url) {
        // URL.toExternalForm invokes its handler. Read only the native URL's stored String fields.
        String protocol=url.getProtocol(), authority=url.getAuthority(), path=url.getPath();
        long length=(protocol==null?0L:protocol.length())+(authority==null?0:authority.length())+(path==null?0:path.length());
        if (length>MAX_ORIGIN_CHARS) refuse("Diagnostic code-origin bound");
        return String.valueOf(protocol)+"://"+String.valueOf(authority)+String.valueOf(path);
    }
    private String classIdentity(Class<?> type) {
        var domain = type.getProtectionDomain();
        var source = domain == null ? null : domain.getCodeSource();
        var url = source == null ? null : source.getLocation();
        return id(type) + " loader=" + id(type.getClassLoader()) + " module=" + id(type.getModule())
                + " origin=" + (url == null ? "unavailable" : origin(url));
    }
    private String id(Object value) {
        if (value == null) return "null";
        Integer number = identities.get(value);
        if (number == null) { if (identities.size() >= 16384) refuse("Diagnostic identity bound");
            metadata.reserve(256); // Before either identity-map or retained-list growth.
            number = identities.size() + 1; identities.put(value, number); retained.add(value); }
        String name=value.getClass().getName(); if(name.length()>1024)refuse("Diagnostic class-name bound");
        return name + "@" + number;
    }
    private void retain(Object value) { id(value); }
    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, false, InstalledSessionCallbackProbe.class.getClassLoader());
    }
    private static Object field(Class<?> owner, Object instance, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (!field.trySetAccessible()) refuse("Inaccessible diagnostic field " + owner.getName() + "#" + name);
        return field.get(instance);
    }
    private void nativePin(Class<?> type) throws java.io.IOException, java.security.NoSuchAlgorithmException {
        pin(type,InstalledObservationNativePins.expected(type.getName()));
    }
    private void pin(Class<?> type, String expected) throws java.io.IOException, java.security.NoSuchAlgorithmException {
        stage="PIN:"+type.getName();
        line("PIN_ORIGIN "+type.getName()+" "+classIdentity(type));
        var resource=type.getResource("/"+type.getName().replace('.','/')+".class");
        line("PIN_RESOURCE "+type.getName()+" observed="+(resource==null?"unavailable":origin(resource)));
        try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (in == null) refuse("Missing exact bus class resource");
            var digest=MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[8192]; int total=0, read;
            while((read=in.read(buffer))!=-1){ total=Math.addExact(total,read); if(total>2*1024*1024)refuse("Class resource bound"); digest.update(buffer,0,read); }
            String actual=HexFormat.of().formatHex(digest.digest());
            line("PIN_BYTES "+type.getName()+" expected="+expected+" actual="+actual);
            if (!expected.equals(actual)) refuse("Pinned class differs: " + type.getName()+" expected="+expected+" actual="+actual);
        }
    }
    private static void refuse(String reason) { throw new ProbeRefusal(reason); }
    static String describeFailure(String stage,Throwable failure) {
        String result="stage="+clip(stage,160)+" type="+clip(failure.getClass().getName(),180);
        // Only our exact final refusal's own immutable String is read. Never call a foreign
        // Throwable's getMessage/toString or walk a possibly arbitrary cause graph.
        if(failure.getClass()==ProbeRefusal.class)result+=" reason="+clip(((ProbeRefusal)failure).reason,512);
        return result;
    }
    private static String clip(String value,int limit){return value==null?"null":value.substring(0,Math.min(value.length(),limit));}
    private static final class ProbeRefusal extends RuntimeException {
        private final String reason;
        ProbeRefusal(String reason){super(reason);this.reason=reason;}
    }
}

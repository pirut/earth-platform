package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.local.LocalChannel;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.util.thread.BlockableEventLoop;

/** Real ordinary LocalChannel constructor/task observation, never a session/adoption capability.
 * All retained endpoint/executor identities are weak. Failure records are prepaid scalar codes.
 * No ordinary packet/world graph is held by this observer after its native callback unwinds.
 */
public final class NativeLocalCausalObservation {
    public static final String FLAG = "earthplatform.nativeLocalFrontierObservation";
    private static final long BYTES = 16_777_472L;
    private static NativeDistanceCpuBudget.Reservation charge;
    private static volatile Attempt retained;
    private static volatile StackWalker walker;
    private static int prechargeFailure;
    private NativeLocalCausalObservation() {}

    private static final class Attempt {
        final Endpoint client = new Endpoint(this, 0), server = new Endpoint(this, 1);
        final WeakReference<SocketAddress> address;
        final WeakReference<?>[] threads=new WeakReference<?>[4];
        final Origin[] origins=new Origin[4];
        final ReadScope[] reads=new ReadScope[4];
        String failedSite, failedHelperFrame, failedOriginalFrame;
        String firstNestedOuter,firstNestedInner,firstNestedFrames;
        int maximumReadDepth;long nestedReads;
        int threadCount;
        private int threadIndex(){Thread current=Thread.currentThread();for(int i=0;i<threadCount;i++)if(threads[i].get()==current)return i;return -1;}
        synchronized Origin currentOrigin(){int i=threadIndex();return i<0?null:origins[i];}
        synchronized void currentOrigin(Origin scope){int i=threadIndex();if(i<0){fail(28);return;}origins[i]=scope;}
        synchronized ReadScope currentRead(){int i=threadIndex();return i<0?null:reads[i];}
        synchronized void currentRead(ReadScope scope){int i=threadIndex();if(i<0){fail(28);return;}reads[i]=scope;}
        synchronized void siteFailure(String site,String helper,String original){
            if(failedSite==null){failedSite=site;failedHelperFrame=helper;failedOriginalFrame=original;}
            fail(1);
        }
        synchronized boolean admitThread(){
            Thread current=Thread.currentThread();
            for(int i=0;i<threadCount;i++)if(threads[i].get()==current)return true;
            if(threadCount==threads.length){fail(28);return false;}
            threads[threadCount++]=new WeakReference<>(current);return true;
        }
        final NativeLocalByteObservation bytes = new NativeLocalByteObservation();
        volatile int failure; int constructors, registrations, connectReturns, activeReturns;
        boolean disconnected;
        Attempt(SocketAddress address) { this.address = new WeakReference<>(address); }
        synchronized void fail(int reason) { if (failure == 0) failure = reason; }
    }
    private static final class Endpoint {
        final Attempt attempt; final int side;
        final NativeOrdinaryTaskFrontier sends = new NativeOrdinaryTaskFrontier(4096);
        final NativeOrdinaryTaskFrontier main = new NativeOrdinaryTaskFrontier(4096);
        final NativeOrdinaryTaskFrontier light;
        WeakReference<Connection> connection;
        volatile WeakReference<Channel> channel, peer;
        WeakReference<Thread> loopThread;
        String channelClass, executorClass, configClass, allocatorClass, peerClass;
        long readEntered, readReturned, directFailures, taskOrigins;
        Endpoint(Attempt attempt, int side) { this.attempt = attempt; this.side = side; this.light = new NativeOrdinaryTaskFrontier(side == 0 ? 8192 : 1); }
    }
    public static final class Origin {
        private final Endpoint endpoint;
        private final NativeOrdinaryTaskFrontier frontier;
        private final NativeOrdinaryTaskFrontier.Ticket ticket;
        private final Origin previous;
        private final int depth;
        private final WeakReference<Thread> owner = new WeakReference<>(Thread.currentThread());
        private Origin(Endpoint endpoint, NativeOrdinaryTaskFrontier frontier, Object executor, Origin previous) {
            this.endpoint = endpoint; this.frontier = frontier; this.ticket = frontier.reserveBeforeFactory(executor); this.previous = previous; this.depth=previous==null?1:previous.depth+1;
        }
    }
    public static final class ReadScope extends NativeOrdinaryInvocationScope {
        private final Endpoint endpoint;
        private WeakReference<Packet<?>> packet;
        private ReadScope(Endpoint endpoint, Packet<?> packet, ReadScope previous) {
            super(previous);this.endpoint = endpoint; this.packet = new WeakReference<>(packet);
        }
    }

    private static boolean enabled() {
        return Boolean.getBoolean(FLAG) && Boolean.getBoolean("earthplatform.installedSessionProbe")
                && !Boolean.getBoolean("earthplatform.installedMarinSession")
                && !Boolean.getBoolean("earthplatform.nativeSessionBridge")
                && !Boolean.getBoolean("neoforge.gameTestServer");
    }
    /** Exact adjacent merged-helper witness. Missing transformation/caller identity only poisons observation. */
    private static boolean site(String owner, String helper) {
        StackWalker exact = walker;
        if (exact == null || retained == null || !retained.admitThread()) return false;
        final Class<?> expected;
        try { expected=Class.forName(owner,false,Connection.class.getClassLoader()); }
        catch(ClassNotFoundException failure){retained.siteFailure(helper,"CLASS_NOT_FOUND",owner);return false;}
        String original=switch(helper){
            case "earthplatform$observeLocalConnect", "earthplatform$observeLocalConstructor" -> "connectToLocalServer";
            case "earthplatform$observeAcceptedConstructor", "earthplatform$observeRosterAdd" -> "initChannel";
            case "earthplatform$observeLocalActive" -> "channelActive";
            case "earthplatform$observeLocalInactive" -> "channelInactive";
            case "earthplatform$observeSendOrigin", "earthplatform$observeSendSubmit" -> "sendPacket";
            case "earthplatform$observePacketOrigin", "earthplatform$observePacketSubmit" -> "ensureRunningOnSameThread";
            case "earthplatform$observeReadOrigin" -> "channelRead0";
            case "earthplatform$observePacketFailure" -> "lambda$ensureRunningOnSameThread$0";
            case "earthplatform$observeDirectFailure" -> "genericsFtw";
            case "earthplatform$observeLightTask" -> "queueLightUpdate";
            case "earthplatform$observeCodecConstructor" -> "<init>";
            case "earthplatform$observeBundleReturn" -> "decode";
            default -> "INVALID";
        };
        return exact.walk(frames -> {
            var chain = frames.limit(5).toList();
            boolean valid = matchesOriginalFrames(chain,expected,helper,original);
            if (!valid && retained != null) retained.siteFailure(helper,frameName(chain,2),
                    frameName(chain,3)+";next="+frameName(chain,4));
            return valid;
        });
    }

    /** Pure shape predicate for tests; only private site() supplies authentic StackWalker frames.
     * The sole @Local accepted-constructor hook has one source-pinned MixinExtras sugar bridge.
     * No ancestor search or bridge allowance exists for any other site.
     */
    static boolean matchesOriginalFrames(List<StackWalker.StackFrame> chain,Class<?> expected,String helper,String original){
        if(chain.size()<4||chain.get(0).getDeclaringClass()!=NativeLocalCausalObservation.class
                ||chain.get(1).getDeclaringClass()!=NativeLocalCausalObservation.class
                ||chain.get(2).getDeclaringClass()!=expected||!chain.get(2).getMethodName().contains(helper))return false;
        int originalIndex=3;
        if(helper.equals("earthplatform$observeAcceptedConstructor")&&original.equals("initChannel")
                &&chain.get(3).getDeclaringClass()==expected
                &&acceptedConstructorBridge(chain.get(3).getMethodName(),chain.get(2).getMethodName()))originalIndex=4;
        return chain.size()>originalIndex&&chain.get(originalIndex).getDeclaringClass()==expected
                &&originalMethod(chain.get(originalIndex).getMethodName(),original);
    }
    private static boolean acceptedConstructorBridge(String observed,String exactHelper){
        String prefix=exactHelper+"$mixinextras$bridge$";
        if(!observed.startsWith(prefix))return false;
        String number=observed.substring(prefix.length());
        if(number.isEmpty()||number.length()>5||number.length()>1&&number.charAt(0)=='0')return false;
        int value=0;for(int i=0;i<number.length();i++){char c=number.charAt(i);if(c<'0'||c>'9')return false;value=value*10+c-'0';}
        return value<=65535; // Native class-file method count bounds UniquenessHelper's decimal suffix.
    }

    private static String frameName(List<StackWalker.StackFrame> frames,int index){
        if(index>=frames.size())return "MISSING";
        var frame=frames.get(index);String owner=frame.getClassName(),method=frame.getMethodName();
        if(owner.length()>120)owner=owner.substring(0,120);if(method.length()>120)method=method.substring(0,120);
        return owner+"#"+method;
    }
    private static boolean originalMethod(String observed,String original){
        if(observed.equals(original))return true;
        String prefix=original+"$mixinextras$wrapped";
        if(!observed.startsWith(prefix))return false;
        String suffix=observed.substring(prefix.length());
        if(suffix.isEmpty())return true;
        if(suffix.length()>5||suffix.charAt(0)!='$')return false;
        for(int i=1;i<suffix.length();i++)if(suffix.charAt(i)<'0'||suffix.charAt(i)>'9')return false;
        return suffix.length()>1;
    }
    /** Called before the original client Connection/Bootstrap factories, not after ordinary login. */
    public static synchronized void beginConnect(SocketAddress address) {
        if (!enabled()) return;
        if (charge != null || retained != null) { if (retained != null) retained.fail(2); return; }
        try {
            charge = NativeDistanceCpuBudget.global().reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA, BYTES);
            retained = new Attempt(address); charge.claimRetainedOwner(retained);
            walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
            if (!NativeLocalInstalledPins.check()) retained.fail(24);
            if (!site("net.minecraft.network.Connection", "earthplatform$observeLocalConnect")) retained.fail(1);
            else if (NativeLocalInstalledPins.qualified()) {
                if(Boolean.getBoolean("earthplatform.nativeLocalRootObservation")) NativeLocalRootObservation.seed();
                if(Boolean.getBoolean("earthplatform.nativeLocalPayloadObservation")) NativeLocalPayloadObservation.seed();
            }
        } catch (RuntimeException failure) { prechargeFailure = 1; if (retained != null) retained.fail(3); }
    }
    public static synchronized void clientConstructed(Connection connection, PacketFlow flow) {
        if (retained == null || !site("net.minecraft.network.Connection", "earthplatform$observeLocalConstructor")) return;
        if (flow != PacketFlow.CLIENTBOUND || connection.getClass() != Connection.class || retained.client.connection != null) { retained.fail(4); return; }
        retained.client.connection = new WeakReference<>(connection); retained.constructors++;
        if(Boolean.getBoolean("earthplatform.nativeLocalRootObservation")) NativeLocalRootObservation.bind(0,connection);
        if(Boolean.getBoolean("earthplatform.nativeLocalPayloadObservation")) NativeLocalPayloadObservation.bind(0,connection);
    }
    public static synchronized void serverConstructed(Connection connection, Channel channel, PacketFlow flow) {
        if (retained == null || !site("net.minecraft.server.network.ServerConnectionListener$2", "earthplatform$observeAcceptedConstructor")) return;
        if (!NativeLocalInstalledPins.qualified() || channel.getClass() != LocalChannel.class) return;
        if (channel.localAddress() != retained.address.get()) return; // Immutable unrelated listener is ignored.
        if (flow != PacketFlow.SERVERBOUND || connection.getClass() != Connection.class || retained.server.connection != null) { retained.fail(5); return; }
        retained.server.connection = new WeakReference<>(connection); retained.server.channel = new WeakReference<>(channel); retained.constructors++;
        if(Boolean.getBoolean("earthplatform.nativeLocalRootObservation")) NativeLocalRootObservation.bind(1,connection);
        if(Boolean.getBoolean("earthplatform.nativeLocalPayloadObservation")) NativeLocalPayloadObservation.bind(1,connection);
    }
    public static synchronized void serverRegistered(Connection connection, boolean accepted) {
        if (retained == null || !site("net.minecraft.server.network.ServerConnectionListener$2", "earthplatform$observeRosterAdd")) return;
        if (get(retained.server.connection) != connection) return;
        if (!accepted || retained.registrations != 0) { retained.fail(6); return; }
        retained.registrations++;
    }
    public static synchronized void connectReturned(Connection connection, boolean failed) {
        if (retained == null || !site("net.minecraft.network.Connection", "earthplatform$observeLocalConnect")) return;
        if (failed || get(retained.client.connection) != connection || retained.connectReturns != 0) { retained.fail(7); return; }
        retained.connectReturns++;
    }
    public static void channelActiveReturned(Connection connection, ChannelHandlerContext context) {
        if (retained == null || !site("net.minecraft.network.Connection", "earthplatform$observeLocalActive")) return;
        Endpoint endpoint = endpoint(connection); if (endpoint == null) return;
        if(!NativeLocalInstalledPins.contextClass(context.getClass())){endpoint.attempt.fail(25);return;}
        Channel channel = context.channel();
        if (channel.getClass() != LocalChannel.class) { endpoint.attempt.fail(8); return; }
        try {
            // These are original exact LocalChannel/DefaultChannelConfig getters on their actual loop.
            Object config = channel.config();
            if (config.getClass() != io.netty.channel.DefaultChannelConfig.class || !NativeLocalInstalledPins.loopClass(channel.eventLoop().getClass(),endpoint.side) || !channel.eventLoop().inEventLoop()) { endpoint.attempt.fail(9); return; }
            Field peer = LocalChannel.class.getDeclaredField("peer"); peer.setAccessible(true);
            Object actualPeer = peer.get(channel);
            if (actualPeer == null || actualPeer.getClass() != LocalChannel.class) { endpoint.attempt.fail(10); return; }
            synchronized (NativeLocalCausalObservation.class) {
                if (endpoint.loopThread != null) { endpoint.attempt.fail(11); return; }
                endpoint.channel = new WeakReference<>(channel); endpoint.peer = new WeakReference<>((Channel)actualPeer);
                endpoint.loopThread = new WeakReference<>(Thread.currentThread());
                endpoint.channelClass = boundedName(channel); endpoint.peerClass = boundedName(actualPeer);
                endpoint.executorClass = boundedName(channel.eventLoop()); endpoint.configClass = boundedName(config);
                endpoint.allocatorClass = boundedName(channel.config().getAllocator()); endpoint.attempt.activeReturns++;
            }
        } catch (ReflectiveOperationException | RuntimeException failure) { endpoint.attempt.fail(12); }
    }
    public static synchronized void channelInactive(Connection connection) {
        if (retained == null || !site("net.minecraft.network.Connection", "earthplatform$observeLocalInactive")) return;
        Endpoint endpoint = endpoint(connection); if (endpoint != null) { endpoint.attempt.disconnected = true; endpoint.attempt.fail(13); }
    }

    public static Origin sendOrigin(Connection connection) {
        if (retained == null || !site("net.minecraft.network.Connection", "earthplatform$observeSendOrigin")) return null;
        Endpoint endpoint = endpoint(connection); if (endpoint == null) return null;
        Channel channel = get(endpoint.channel);
        if (channel == null || channel.getClass() != LocalChannel.class) { endpoint.attempt.fail(14); return null; }
        Origin previous = retained.currentOrigin(); if (previous != null && previous.depth >= 16) { endpoint.attempt.fail(15); return null; }
        Origin scope = new Origin(endpoint, endpoint.sends, channel.eventLoop(), previous); retained.currentOrigin(scope); return scope;
    }
    public static Origin packetOrigin(Packet<?> packet, PacketListener listener, BlockableEventLoop<?> executor) {
        if (retained == null || !site("net.minecraft.network.protocol.PacketUtils", "earthplatform$observePacketOrigin")) return null;
        ReadScope read = retained.currentRead();
        if (read == null) return null; // Main-thread repeated ensureRunning is inside the already counted original callback.
        Connection receiver=get(read.endpoint.connection);
        if(receiver==null||receiver.getPacketListener()!=listener
                ||executor.getClass()!=(read.endpoint.side==0?net.minecraft.client.Minecraft.class:net.minecraft.client.server.IntegratedServer.class)){
            read.endpoint.attempt.fail(26);return null;
        }
        if (read.packet.get() != packet) { read.endpoint.attempt.fail(16); return null; }
        Origin previous = retained.currentOrigin(); if (previous != null && previous.depth >= 16) { read.endpoint.attempt.fail(15); return null; }
        Origin scope = new Origin(read.endpoint, read.endpoint.main, executor, previous); retained.currentOrigin(scope);
        synchronized (read.endpoint) { read.endpoint.taskOrigins++; }
        return scope;
    }
    public static Runnable taskAtSubmission(Object executor, Runnable original, boolean packetTask) {
        String owner = packetTask ? "net.minecraft.network.protocol.PacketUtils" : "net.minecraft.network.Connection";
        String helper = packetTask ? "earthplatform$observePacketSubmit" : "earthplatform$observeSendSubmit";
        if (retained == null || !site(owner, helper)) return original;
        Origin scope = retained.currentOrigin(); if (scope == null) return original;
        return scope.frontier.envelopeAtOriginalSubmission(scope.ticket, executor, original);
    }
    public static void submissionReturned(boolean packetTask, boolean failed) {
        String owner = packetTask ? "net.minecraft.network.protocol.PacketUtils" : "net.minecraft.network.Connection";
        String helper = packetTask ? "earthplatform$observePacketSubmit" : "earthplatform$observeSendSubmit";
        if (retained == null || !site(owner, helper)) return;
        Origin scope = retained.currentOrigin(); if (scope != null) scope.frontier.originalSubmissionReturned(scope.ticket, failed);
    }
    public static void originFailed(Origin scope,boolean packetTask){
        String owner=packetTask?"net.minecraft.network.protocol.PacketUtils":"net.minecraft.network.Connection";
        String helper=packetTask?"earthplatform$observePacketOrigin":"earthplatform$observeSendOrigin";
        if(scope!=null&&site(owner,helper))scope.frontier.sourceLost();
    }
    public static void directHandlerFailed(){
        if(retained==null||!site("net.minecraft.network.Connection","earthplatform$observeDirectFailure"))return;
        ReadScope scope=retained.currentRead();if(scope!=null)scope.endpoint.attempt.fail(27);
    }
    public static void originReturned(Origin scope, boolean packetTask) {
        String owner = packetTask ? "net.minecraft.network.protocol.PacketUtils" : "net.minecraft.network.Connection";
        String helper = packetTask ? "earthplatform$observePacketOrigin" : "earthplatform$observeSendOrigin";
        if (scope == null || !site(owner, helper)) return;
        if (scope.owner.get() != Thread.currentThread() || retained.currentOrigin() != scope) { scope.endpoint.attempt.fail(17); return; }
        retained.currentOrigin(scope.previous); scope.frontier.originalOriginReturned(scope.ticket);
    }

    public static ReadScope readEntered(Connection connection, Packet<?> packet) {
        if (retained == null || !site("net.minecraft.network.Connection", "earthplatform$observeReadOrigin")) return null;
        Endpoint endpoint = endpoint(connection); if (endpoint == null || !NativeLocalInstalledPins.qualified()) return null;
        ReadScope previous = retained.currentRead();
        if(!NativeOrdinaryInvocationScope.canEnter(previous)){endpoint.attempt.fail(18);return null;}
        ReadScope scope = new ReadScope(endpoint, packet, previous); retained.currentRead(scope);
        synchronized(endpoint.attempt){
            endpoint.attempt.maximumReadDepth=Math.max(endpoint.attempt.maximumReadDepth,scope.depth);
            if(previous!=null){
                endpoint.attempt.nestedReads++;
                if(endpoint.attempt.firstNestedFrames==null){
                    Packet<?> outer=previous.packet==null?null:previous.packet.get();
                    endpoint.attempt.firstNestedOuter=outer==null?"MISSING":boundedName(outer);
                    endpoint.attempt.firstNestedInner=boundedName(packet);
                    // Bounded diagnostic evidence only. This stack does not authenticate a producer.
                    endpoint.attempt.firstNestedFrames=walker.walk(frames->{
                        var chain=frames.limit(64).toList();StringBuilder result=new StringBuilder(16000);
                        for(int i=0;i<chain.size();i++){if(i>0)result.append(';');result.append(frameName(chain,i));}
                        return result.toString();
                    });
                }
            }
        }
        synchronized (endpoint) { endpoint.readEntered++; } return scope;
    }
    public static void readReturned(ReadScope scope, boolean failed) {
        if (scope == null || !site("net.minecraft.network.Connection", "earthplatform$observeReadOrigin")) return;
        if (!scope.returnedFrom(retained.currentRead())) { scope.endpoint.attempt.fail(19); return; }
        scope.packet = null; retained.currentRead((ReadScope)scope.previous);
        synchronized (scope.endpoint) { scope.endpoint.readReturned++; if (failed) scope.endpoint.directFailures++; }
        if (failed) scope.endpoint.attempt.fail(20);
    }
    public static void packetHandlerFailed() {
        if (retained == null || !site("net.minecraft.network.protocol.PacketUtils", "earthplatform$observePacketFailure")) return;
        // This callback occurs within the original scheduled lambda, before PacketUtils' catch.
        // No Throwable or listener/packet graph is retained by the ordinary observer.
        NativeOrdinaryTaskFrontier active = NativeOrdinaryTaskFrontier.active();
        if (active != null) active.originalCallbackFailed();
    }

    public static Origin lightOrigin(Object level) {
        if (retained == null || !site("net.minecraft.client.multiplayer.ClientLevel", "earthplatform$observeLightTask")) return null;
        NativeOrdinaryTaskFrontier active = NativeOrdinaryTaskFrontier.active();
        if (active != retained.client.main) { retained.fail(22); return null; }
        Origin previous = retained.currentOrigin(); if (previous != null && previous.depth >= 16) { retained.fail(15); return null; }
        Origin scope = new Origin(retained.client, retained.client.light, level, previous); retained.currentOrigin(scope); return scope;
    }
    public static Runnable lightTask(Origin scope, Object level, Runnable original) {
        if (scope == null || !site("net.minecraft.client.multiplayer.ClientLevel", "earthplatform$observeLightTask")) return original;
        return scope.frontier.envelopeAtOriginalSubmission(scope.ticket, level, original);
    }
    public static void lightSubmitted(Origin scope, boolean failed) {
        if (scope == null || !site("net.minecraft.client.multiplayer.ClientLevel", "earthplatform$observeLightTask")) return;
        scope.frontier.originalSubmissionReturned(scope.ticket, failed);
        if (scope.owner.get() != Thread.currentThread() || retained.currentOrigin() != scope) { scope.endpoint.attempt.fail(17); return; }
        retained.currentOrigin(scope.previous); scope.frontier.originalOriginReturned(scope.ticket);
    }
    /** Observer-only failure, no Throwable/native graph retention and no ordinary callback cancellation. */
    public static void observerFailed() { Attempt attempt = retained; if (attempt != null) attempt.fail(23); }

    static int sideOf(Channel channel) {
        Attempt attempt=retained; if(attempt==null)return -1;
        if(get(attempt.client.channel)==channel)return 0;
        if(get(attempt.server.channel)==channel)return 1;
        return -1;
    }
    public static void codecConstructed(Object codec,Object protocol,boolean outbound){
        Attempt a=retained;
        if(a==null||!NativeLocalInstalledPins.qualified())return;
        String owner=outbound?"net.minecraft.network.PacketEncoder":"net.minecraft.network.PacketDecoder";
        if(!site(owner,"earthplatform$observeCodecConstructor"))return;
        a.bytes.constructor(codec,protocol,outbound);
    }
    public static void bundleReturned(io.netty.channel.ChannelHandlerContext context,boolean partial,boolean failed){
        Attempt a=retained;
        if(a==null||!site("net.minecraft.network.PacketBundlePacker","earthplatform$observeBundleReturn"))return;
        if(!NativeLocalInstalledPins.contextClass(context.getClass())){a.fail(25);return;}
        a.bytes.bundle(sideOf(context.channel()),partial,failed);
    }
    static NativeLocalByteObservation byteObservation(){Attempt a=retained;return a==null?null:a.bytes;}
    private static synchronized Endpoint endpoint(Connection exact) {
        Attempt attempt = retained; if (attempt == null) return null;
        if (get(attempt.client.connection) == exact) return attempt.client;
        if (get(attempt.server.connection) == exact) return attempt.server;
        return null;
    }
    private static <T> T get(WeakReference<T> reference) { return reference == null ? null : reference.get(); }
    private static String boundedName(Object value) {
        String name = value.getClass().getName(); if (name.length() > 256) return "CLASS_NAME_OVERFLOW"; return name;
    }
    /** Server-owner report reads only observer scalars/weak identity equality; no channel/Host dispatch. */
    public static synchronized List<String> snapshot() {
        if (!enabled()) return List.of("LOCAL_FRONTIER disabled");
        Attempt attempt = retained;
        if (attempt == null) return List.of("LOCAL_FRONTIER absent prechargeFailure=" + prechargeFailure);
        var lines = new ArrayList<String>(10);
        boolean pair = get(attempt.client.channel) != null && get(attempt.server.channel) != null
                && get(attempt.client.peer) == get(attempt.server.channel) && get(attempt.server.peer) == get(attempt.client.channel);
        var global=NativeDistanceCpuBudget.global().evidence();
        lines.add("LOCAL_BUDGET observer="+BYTES+" metadataUsed="+global.pools().get(NativeDistanceCpuBudget.Pool.OWNER_METADATA)
                +" metadataCap="+NativeDistanceCpuBudget.Pool.OWNER_METADATA.limit()+" globalBytes="+global.bytes()+" globalCap="+NativeDistanceCpuBudget.MAX_BYTES);
        lines.add("LOCAL_PINS profile="+NativeLocalInstalledPins.PROFILE+" checked="+NativeLocalInstalledPins.qualified()+" namedClient="+NativeLocalInstalledPins.NAMED_CLIENT+" patchedClient="+NativeLocalInstalledPins.PATCHED_CLIENT);
        lines.add("LOCAL_FRONTIER constructors="+attempt.constructors+" registered="+attempt.registrations+" connectReturns="+attempt.connectReturns
                +" activeReturns="+attempt.activeReturns+" exactPeerPair="+pair+" failure="+attempt.failure+" chargedBytes="+BYTES);
        synchronized(attempt){if(attempt.failedSite!=null)lines.add("LOCAL_SITE_FAILURE site="+attempt.failedSite+" helper="+attempt.failedHelperFrame+" original="+attempt.failedOriginalFrame);}
        synchronized(attempt){
            lines.add("LOCAL_READ_SCOPES nested="+attempt.nestedReads+" maximumDepth="+attempt.maximumReadDepth+" limit="+NativeOrdinaryInvocationScope.LIMIT);
            if(attempt.firstNestedFrames!=null)lines.add("LOCAL_FIRST_NESTED_READ outer="+attempt.firstNestedOuter+" inner="+attempt.firstNestedInner+" frames="+attempt.firstNestedFrames);
        }
        lines.add(NativeLocalInstalledPins.failureSummary());
        append(lines, attempt.client); append(lines, attempt.server);
        lines.add(attempt.bytes.summary());
        attempt.bytes.appendProfiles(lines);
        if(Boolean.getBoolean("earthplatform.nativeLocalRootObservation")) NativeLocalRootObservation.append(lines);
        if(Boolean.getBoolean("earthplatform.nativeLocalPayloadObservation")) NativeLocalPayloadObservation.append(lines);
        lines.add("LOCAL_CUT admissible=false missing=main_payload_dispatch_aliases,generic_packet_splitter_roots,pipeline_callback_closure,allocator_backing_bound,pending_configuration_and_consumer_roots,client_light_engine_and_render_continuations,private_adoption_binding");
        lines.add("LOCAL_FRONTIER_DIAGNOSTIC_ONLY ordinaryExecutionPreserved=true hostOrEntryAuthority=false");
        return List.copyOf(lines);
    }
    private static void append(List<String> lines, Endpoint endpoint) {
        synchronized (endpoint) {
            lines.add("LOCAL_ENDPOINT side="+endpoint.side+" channel="+endpoint.channelClass+" peer="+endpoint.peerClass
                    +" loop="+endpoint.executorClass+" config="+endpoint.configClass+" allocator="+endpoint.allocatorClass);
            lines.add("LOCAL_TASKS side="+endpoint.side+" sendLineage="+endpoint.sends.lineage()+" mainLineage="+endpoint.main.lineage()
                    +" sendSettled="+endpoint.sends.observedPayloadsSettled()+" mainSettled="+endpoint.main.observedPayloadsSettled()
                    +" lightLineage="+endpoint.light.lineage()+" lightSettled="+endpoint.light.observedPayloadsSettled()
                    +" sendFailure="+endpoint.sends.failure()+" mainFailure="+endpoint.main.failure()+" lightFailure="+endpoint.light.failure()
                    +" readEntered="+endpoint.readEntered+" readReturned="+endpoint.readReturned+" directFailures="+endpoint.directFailures);
        }
    }
}

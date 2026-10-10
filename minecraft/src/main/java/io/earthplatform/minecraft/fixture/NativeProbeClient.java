package io.earthplatform.minecraft.fixture;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.residency.SparseFacadeException;
import io.earthplatform.minecraft.renderprobe.NativeRenderSession;
import io.earthplatform.streaming.CubeStreamAdmission;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.block.Blocks;

/** Runs only inside the ordinary initialized development client. Never substitutes a headless client. */
public final class NativeProbeClient {
    private static long lastEpoch;
    private static net.minecraft.client.multiplayer.ClientPacketListener lastConnection;
    private NativeProbeClient() {}
    public static void handle(NativeProbePayload payload) {
        try { run(payload); }
        catch(IOException failure) { EarthPlatformMod.LOGGER.error("Native sparse client diagnostic I/O failure",failure);throw new UncheckedIOException(failure); }
    }
    private static CubeStreamAdmission.Header header(EngineCubeSnapshot snapshot) {
        return new CubeStreamAdmission.Header(snapshot.world(),snapshot.epoch(),snapshot.position(),snapshot.leaseGeneration(),snapshot.revision());
    }
    private static EngineCubeSnapshot read(byte[] bytes,NativeSparseBinding binding,long epoch) {
        var buffer=new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try { return EngineCubeSnapshot.readFrame(buffer,NativeProbeChecks.world(binding),epoch,binding.palette()); }
        finally { buffer.release(); }
    }
    private static void install(EngineCubeSnapshot snapshot,NativeSparseBinding binding,CubeStreamAdmission gate) {
        var admission=gate.admit(header(snapshot));
        NativeProbeChecks.require(admission.accepted(),"snapshot admitted: "+admission.rejection());
        NativeProbeChecks.require(gate.commit(admission.token(),()->binding.cubes().installAdmittedSnapshot(snapshot))==CubeStreamAdmission.Rejection.NONE,
                "actual synchronous owner-thread installation");
    }
    private static void run(NativeProbePayload payload) throws IOException {
        Minecraft minecraft=Minecraft.getInstance();
        NativeProbeChecks.require(minecraft!=null&&minecraft.isSameThread(),"actual client-thread ownership");
        NativeProbeChecks.require(minecraft.level!=null&&minecraft.getConnection()!=null&&minecraft.getSingleplayerServer()!=null,"initialized integrated-client context");
        if(lastConnection!=minecraft.getConnection()){lastConnection=minecraft.getConnection();lastEpoch=0;}
        if(payload.epoch()<=lastEpoch)throw new SparseFacadeException("stale diagnostic request");
        lastEpoch=payload.epoch();
        ClientLevel fixture;
        try(var scope=SparseFixtureConstruction.open(SparseFixtureConstruction.Endpoint.CLIENT,minecraft.level.dimensionType())) {
            fixture=new ClientLevel(minecraft.getConnection(),new ClientLevel.ClientLevelData(Difficulty.PEACEFUL,false,false),
                    SparseFixtureConstruction.DIMENSION,Holder.direct(scope.type()),2,2,()->InactiveProfiler.INSTANCE,minecraft.levelRenderer,false,0L);
            scope.verify(fixture);
        }
        var binding=NativeSparseBinding.find(fixture);
        var gate=new CubeStreamAdmission(NativeProbeChecks.world(binding),payload.epoch(),2);
        boolean transferredToRenderer=false;
        try {
            gate.replaceInterest(List.of(NativeSparseBinding.LOWER,NativeSparseBinding.UPPER));
            var lower=read(payload.lower(),binding,payload.epoch());var upper=read(payload.upper(),binding,payload.epoch());
            install(lower,binding,gate);install(upper,binding,gate);
            var report=NativeProbeChecks.observe(binding,payload.epoch(),"client");
            NativeProbeChecks.rejects(()->fixture.setBlock(NativeProbeChecks.UPPER_BLOCK,Blocks.AIR.defaultBlockState(),16),"client authoritative mutation");
            gate.replaceInterest(List.of(NativeSparseBinding.UPPER));binding.cubes().retire(NativeSparseBinding.LOWER);
            NativeProbeChecks.verifyLowerRetired(binding);
            NativeProbeChecks.require(!gate.admit(header(lower)).accepted(),"removed lease rejects late frame");
            var renewed=gate.replaceInterest(List.of(NativeSparseBinding.LOWER,NativeSparseBinding.UPPER));
            NativeProbeChecks.require(renewed.requested().size()==1&&renewed.requested().getFirst().generation()==3,"renewed generation differs without changing upper");
            NativeProbeChecks.require(gate.admit(header(lower)).rejection()==CubeStreamAdmission.Rejection.RETIRED_LEASE,"same-epoch ABA frame rejected");
            var renewedLower=read(payload.renewedLower(),binding,payload.epoch());
            install(renewedLower,binding,gate);
            NativeProbeChecks.observe(binding,payload.epoch(),"client");
            NativeProbeChecks.require(gate.view().metrics().loadedLeases()==2,"two admitted loaded owners after re-add");
            report.addProperty("realCustomPayloadHandler",true);report.addProperty("forcedInnerFrameCodec",true);
            report.addProperty("generationQualifiedRetireReload",true);report.addProperty("staleLeaseRejected",true);
            report.addProperty("clientMutationRejected",true);report.addProperty("status","PASS");
            var path=NativeProbeChecks.write(report,"client",payload.epoch());
            EarthPlatformMod.LOGGER.info("NATIVE_SPARSE_CLIENT_PASS {}",path);
            minecraft.gui.getChat().addMessage(Component.literal("Native sparse fixture PASS: actual ServerLevel/ClientLevel, Y -11000/+8849, 2 cube owners, zero tall arrays. No rendered full-height gameplay yet."));
            if(Boolean.getBoolean(NativeRenderSession.OPT_IN)) {
                var session=NativeRenderSession.takeOwnership(fixture,binding,gate,renewedLower,upper);
                transferredToRenderer=true;session.open();
            }
        } finally {
            if(!transferredToRenderer){gate.close();binding.beginDisposableClose();fixture.close();}
        }
    }
}

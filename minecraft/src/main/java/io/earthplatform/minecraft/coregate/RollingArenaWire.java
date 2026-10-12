package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.*;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import net.minecraft.network.FriendlyByteBuf;

/** Exact arena wire capability. Packed bytes transfer directly into their immutable owner. */
final class RollingArenaWire {
    private RollingArenaWire() {}
    record Body(ArenaCertificate certificate,ArenaCapture capture) {}
    static boolean arenaWorld(WorldIdentity world) { return NativeArenaCubeProvider.GENERATOR_ID.equals(world.generatorId())&&NativeArenaCubeProvider.GENERATOR_VERSION.equals(world.generatorVersion()); }
    static void write(FriendlyByteBuf b,ArenaCertificate c,ArenaCapture capture) {
        if(c==null||capture==null)throw new IllegalArgumentException("Complete arena authority required");
        c.verifyCapture(capture);b.writeUtf(c.definition().identity(),64);b.writeLong(c.resourceGeneration());
        b.writeUtf(c.storeIdentity(),36);b.writeUtf(c.indexIdentity(),64);b.writeUtf(c.providerIdentity(),128);b.writeUtf(c.paletteIdentity(),64);
        b.writeUtf(c.materialPolicy(),128);b.writeUtf(c.detailPolicy(),128);b.writeLong(c.generation());
        b.writeVarInt(64);for(var v:c.dependencies()){CoreGatePayload.position(b,v.position());b.writeLong(v.leaseGeneration());b.writeLong(v.revision());}
        b.writeUtf(c.captureIdentity(),64);var q=c.queryAttestation();
        b.writeLong(q.snapshotGeneration());b.writeUtf(q.snapshotRootIdentity(),64);b.writeUtf(q.layoutIdentity(),1024);b.writeUtf(q.policyIdentity(),64);b.writeUtf(q.resultDigest(),64);b.writeVarInt(q.resultCount());
        b.writeVarInt(ArenaCapture.PACKED_BYTES);
        try{capture.write(new ByteBufOutputStream(b));}catch(IOException impossible){throw new IllegalStateException("Arena wire output failed",impossible);}
    }
    static Body read(FriendlyByteBuf b,WorldIdentity world,long epoch) {
        var definition=ArenaDefinition.FIXED;
        if(!arenaWorld(world)||!definition.identity().equals(b.readUtf(64)))throw new IllegalArgumentException("Unknown arena wire source/definition");
        long resources=b.readLong();String store=b.readUtf(36),index=b.readUtf(64),provider=b.readUtf(128),palette=b.readUtf(64),material=b.readUtf(128),detail=b.readUtf(128);long generation=b.readLong();
        int count=b.readVarInt();if(count!=64||b.readableBytes()<64*28)throw new IllegalArgumentException("Complete64 arena dependency wire required");
        var versions=new ArrayList<RollingCubeActor.CubeVersion>(64);for(int i=0;i<64;i++)versions.add(new RollingCubeActor.CubeVersion(CoreGatePayload.position(b),b.readLong(),b.readLong()));
        String captureId=b.readUtf(64);var query=new ArenaQueryAttestation(store,index,b.readLong(),b.readUtf(64),definition.identity(),b.readUtf(1024),b.readUtf(64),b.readUtf(64),b.readVarInt());
        var certificate=new ArenaCertificate(world,epoch,resources,store,index,provider,palette,material,detail,generation,definition,versions,captureId,query);
        int bytes=b.readVarInt();if(bytes!=ArenaCapture.PACKED_BYTES||b.readableBytes()<bytes)throw new IllegalArgumentException("Exact packed arena byte count required");
        try{var capture=ArenaCapture.read(new ByteBufInputStream(b.readSlice(bytes)),definition);certificate.verifyCapture(capture);return new Body(certificate,capture);}
        catch(IOException failure){throw new IllegalArgumentException("Truncated packed arena",failure);}
    }
}

package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.lod.DhLodLayout;
import io.earthplatform.minecraft.enginebridge.RollingEditPolicy;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent literal v1 encoder transcribed from frozen native-earth-player-003. No terrain reads. */
class RollingLegacyIdentityIndependentReviewTest {
    @Test void earthStoreAndSourceRetainEveryFrozenV1CanonicalByteAcrossPolicyV3() throws Exception {
        for (int i=0;i<64;i++) {
            long x=-30_000_000L+i*96749L,z=30_000_000L-i*31127L;int y=-10_976+i*307;
            var world=new WorldIdentity(Long.MIN_VALUE+i,"review-source-α","v1/unchanged",String.format("%064x",i),"palette-v1");
            var pin=CubePos.fromBlock(x,y,z);var entry=new Vec3(x+.5,y+3,z+.5);String palette="9".repeat(64);
            var body=new RollingSessionDescriptor.BodyEnvelope(x-128,x+128,-10976,8832,z-128,z+128);
            var windows=new RollingSessionDescriptor.WindowEnvelope(CubePos.section(x-128),CubePos.section(x+127),-686,551,CubePos.section(z-128),CubePos.section(z+127));
            var ctor=RollingSessionDescriptor.class.getDeclaredConstructor(WorldIdentity.class,String.class,String.class,String.class,
                    CubePos.class,CubePos.class,Vec3.class,int.class,long.class,long.class,boolean.class,DhLodLayout.class,RollingEditPolicy.class,
                    RollingSessionDescriptor.BodyEnvelope.class,RollingSessionDescriptor.WindowEnvelope.class,boolean.class,boolean.class);
            ctor.setAccessible(true);
            var actual=ctor.newInstance(world,"earth","earth",palette,pin,pin,entry,y,x,z,true,DhLodLayout.FIXED_512,RollingEditPolicy.INTERIOR_V1,body,windows,false,false);
            var sourceBytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(sourceBytes)){string(out,"rolling-session-descriptor-v1/source");world(out,world);}
            String oldSource=hash(sourceBytes.toByteArray());assertEquals(oldSource,actual.sourceFingerprint());
            var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){
                string(out,"rolling-session-descriptor-v1");world(out,world);string(out,"earth");string(out,"earth");string(out,palette);
                position(out,pin);position(out,pin);out.writeDouble(entry.x);out.writeDouble(entry.y);out.writeDouble(entry.z);
                out.writeInt(y);out.writeLong(x);out.writeLong(z);out.writeBoolean(true);out.writeInt(512);
                out.writeLong(body.minX());out.writeLong(body.maxX());out.writeInt(body.minY());out.writeInt(body.maxY());out.writeLong(body.minZ());out.writeLong(body.maxZ());
                out.writeInt(windows.minX());out.writeInt(windows.maxX());out.writeInt(windows.minY());out.writeInt(windows.maxY());out.writeInt(windows.minZ());out.writeInt(windows.maxZ());string(out,oldSource);
            }
            String oldPolicy=hash(bytes.toByteArray());
            assertEquals(oldPolicy,frozenV1StoreFingerprint(actual,palette));
            assertEquals("earthplatform-earth-player-gate-v1/"+oldPolicy,actual.saveDirectory());
            assertNotEquals(oldPolicy,actual.fingerprint(),"New session policy must not reuse the old authority digest");
            assertEquals(512,actual.fixedFarRadius());assertEquals(DhLodLayout.FIXED_512,actual.lodLayout());
        }
    }
    /** Frozen 003 serialization oracle; intentionally independent of production canonicalBytes(). */
    static String frozenV1StoreFingerprint(RollingSessionDescriptor descriptor,String paletteFingerprint)throws Exception {
        var source=new ByteArrayOutputStream();try(var out=new DataOutputStream(source)){
            string(out,"rolling-session-descriptor-v1/source");world(out,descriptor.world());
        }
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){
            string(out,"rolling-session-descriptor-v1");world(out,descriptor.world());
            string(out,descriptor.profileKind());string(out,descriptor.profileId());string(out,paletteFingerprint);
            position(out,descriptor.pinCenter());position(out,descriptor.initialCenter());
            var entry=descriptor.entry();out.writeDouble(entry.x);out.writeDouble(entry.y);out.writeDouble(entry.z);
            out.writeInt(descriptor.anchorY());out.writeLong(descriptor.fixedFarCenterX());out.writeLong(descriptor.fixedFarCenterZ());
            out.writeBoolean(descriptor.naturalRelief());out.writeInt(512);
            var body=descriptor.bodyEnvelope();out.writeLong(body.minX());out.writeLong(body.maxX());out.writeInt(body.minY());out.writeInt(body.maxY());out.writeLong(body.minZ());out.writeLong(body.maxZ());
            var windows=descriptor.windowEnvelope();out.writeInt(windows.minX());out.writeInt(windows.maxX());out.writeInt(windows.minY());out.writeInt(windows.maxY());out.writeInt(windows.minZ());out.writeInt(windows.maxZ());
            string(out,hash(source.toByteArray()));
        }
        return hash(bytes.toByteArray());
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void string(DataOutputStream out,String value)throws Exception{byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
    private static void position(DataOutputStream out,CubePos p)throws Exception{out.writeInt(p.x());out.writeInt(p.y());out.writeInt(p.z());}
    private static void world(DataOutputStream out,WorldIdentity w)throws Exception{out.writeLong(w.seed());string(out,w.generatorId());string(out,w.generatorVersion());string(out,w.datasetVersion());string(out,w.blockPaletteVersion());}
}

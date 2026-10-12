package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.enginebridge.*;
import net.minecraft.network.FriendlyByteBuf;

/** Versioned Earth-only compact payload. The paired manifest binds its content digest. */
final class RollingEarthReplacementWire {
    private RollingEarthReplacementWire() {}
    static boolean earthWorld(WorldIdentity world) {
        return NativeAlpineCubeProvider.GENERATOR_ID.equals(world.generatorId())
                && (NativeAlpineCubeProvider.GENERATOR_VERSION+"/"+RollingGateFixture.VERSION).equals(world.generatorVersion())
                && world.seed()==EarthAlpineDefinition.SEED
                && EarthAlpineDefinition.DEFINITION_IDENTITY.equals(world.datasetVersion());
    }
    static void require(ReplacementCertificate c,EarthReplacementCapture capture) {
        if(c==null||capture==null||!earthWorld(c.world())||!c.originalCube().equals(capture.position())
                ||!c.providerIdentity().equals(c.world().datasetVersion())
                ||!EarthReplacementAdmission.MATERIAL_POLICY.equals(c.materialPolicy())
                ||!EarthReplacementAdmission.DETAIL_POLICY.equals(c.detailPolicy())
                ||!c.originalCube().equals(io.earthplatform.core.CubePos.fromBlock(EarthAlpineDefinition.ANCHOR_X,
                    EarthAlpineDefinition.GROUND_Y,EarthAlpineDefinition.ANCHOR_Z)))
            throw new IllegalArgumentException("Exact Earth source, palette capability and original cube required");
        if(capture.cellCount()!=8000||capture.declaredBytes()!=8000)
            throw new IllegalArgumentException("Exact Earth 20-cubed byte capture required");
    }
    static void write(FriendlyByteBuf b,ReplacementCertificate c,EarthReplacementCapture capture) {
        require(c,capture);b.writeUtf(capture.contentIdentity(),64);b.writeVarInt(capture.cellCount());
        for(long y=capture.minY();y<capture.maxYExclusive();y++)
            for(long z=capture.minZ();z<capture.maxZExclusive();z++)
                for(long x=capture.minX();x<capture.maxXExclusive();x++)b.writeByte(capture.materialAt(x,y,z));
    }
    static EarthReplacementCapture read(FriendlyByteBuf b,ReplacementCertificate c) {
        if(c==null||!earthWorld(c.world()))throw new IllegalArgumentException("Earth wire certificate required");
        String digest=b.readUtf(64);int count=b.readVarInt();
        if(!digest.matches("[0-9a-f]{64}")||count!=8000||b.readableBytes()<count)
            throw new IllegalArgumentException("Exact bounded Earth replacement bytes required");
        try(var builder=new EarthReplacementCapture.Builder(c.originalCube())) {
            for(int i=0;i<count;i++)builder.setNext(b.readUnsignedByte());
            var capture=builder.finish();require(c,capture);
            if(!digest.equals(capture.contentIdentity()))throw new IllegalArgumentException("Earth capture digest mismatch");
            return capture;
        }
    }
}

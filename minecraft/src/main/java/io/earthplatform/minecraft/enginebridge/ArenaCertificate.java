package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One exact immutable union certificate, with all eight durable revisions derived from 64 dependencies. */
public record ArenaCertificate(WorldIdentity world,long epoch,long resourceGeneration,String storeIdentity,
        String indexIdentity,String providerIdentity,String paletteIdentity,String materialPolicy,String detailPolicy,
        long generation,ArenaDefinition definition,List<RollingCubeActor.CubeVersion> dependencies,
        String captureIdentity,ArenaQueryAttestation queryAttestation) {
    public ArenaCertificate {
        Objects.requireNonNull(world);Objects.requireNonNull(definition);Objects.requireNonNull(dependencies);Objects.requireNonNull(queryAttestation);
        if(epoch<0||resourceGeneration<=0||generation<=0||!UUID.fromString(storeIdentity).toString().equals(storeIdentity))throw new IllegalArgumentException("Invalid arena publication generation/store");
        boundedWorld(world.generatorId());boundedWorld(world.generatorVersion());boundedWorld(world.datasetVersion());boundedWorld(world.blockPaletteVersion());
        ReplacementCertificate.bounded(providerIdentity);ReplacementCertificate.bounded(materialPolicy);ReplacementCertificate.bounded(detailPolicy);
        ArenaQueryAttestation.digest(indexIdentity);ArenaQueryAttestation.digest(paletteIdentity);ArenaQueryAttestation.digest(captureIdentity);
        if(dependencies.size()!=ArenaDefinition.DEPENDENCY_COUNT)throw new IllegalArgumentException("Complete64 arena dependencies required");
        dependencies=List.copyOf(dependencies);
        if(!dependencies.stream().map(RollingCubeActor.CubeVersion::position).toList().equals(definition.dependencies())||dependencies.stream().map(RollingCubeActor.CubeVersion::leaseGeneration).distinct().count()!=64)throw new IllegalArgumentException("Canonical distinct64 dependencies required");
        if(dependencies.stream().anyMatch(v->!definition.contains(v.position())&&v.revision()!=0))throw new IllegalArgumentException("Only eight arena cubes may be edited");
        if(!storeIdentity.equals(queryAttestation.storeIdentity())||!indexIdentity.equals(queryAttestation.indexIdentity())||!definition.identity().equals(queryAttestation.definitionIdentity()))throw new IllegalArgumentException("Foreign arena query authority");
        queryAttestation.verify(dependencies,captureIdentity);
    }
    private static void boundedWorld(String value){if(value==null||value.isBlank()||value.length()>128)throw new IllegalArgumentException("Bounded full world identity required");}
    public long revision(CubePos position){if(!definition.contains(position))throw new IllegalArgumentException("Cube is not in fixed arena");return dependencies.stream().filter(v->v.position().equals(position)).findFirst().orElseThrow().revision();}
    public void verifyCapture(ArenaCapture capture){if(capture==null||!definition.identity().equals(capture.definition().identity())||!captureIdentity.equals(capture.contentIdentity()))throw new IllegalArgumentException("Capture does not match arena certificate");}
    public String identity(){return identity(true);} public String durableIdentity(){return identity(false);} public String dependencyIdentity(){return durableIdentity();}
    private String identity(boolean publication){
        try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(bytes)){
            out.writeUTF(publication?"earth-arena-publication-v1":"earth-arena-durable-v1");out.writeLong(world.seed());out.writeUTF(world.generatorId());out.writeUTF(world.generatorVersion());out.writeUTF(world.datasetVersion());out.writeUTF(world.blockPaletteVersion());
            if(publication){out.writeLong(epoch);out.writeLong(resourceGeneration);out.writeLong(generation);}out.writeUTF(storeIdentity);out.writeUTF(indexIdentity);out.writeUTF(providerIdentity);out.writeUTF(paletteIdentity);out.writeUTF(materialPolicy);out.writeUTF(detailPolicy);out.writeUTF(definition.identity());out.writeUTF(captureIdentity);
            for(var v:dependencies){out.writeInt(v.position().x());out.writeInt(v.position().y());out.writeInt(v.position().z());out.writeLong(v.revision());if(publication)out.writeLong(v.leaseGeneration());}queryAttestation.write(out);
        }return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));}catch(IOException|NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}

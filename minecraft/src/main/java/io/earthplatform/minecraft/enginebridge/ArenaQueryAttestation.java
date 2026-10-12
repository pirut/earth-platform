package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.WorldIdentity;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Complete whole-domain eight-key catalogue value. Decoding never grants publication authority. */
public record ArenaQueryAttestation(String storeIdentity,String indexIdentity,long snapshotGeneration,
        String snapshotRootIdentity,String definitionIdentity,String layoutIdentity,String policyIdentity,
        String resultDigest,int resultCount) {
    public static final String VERSION="earth-fixed-arena-complete-query-v1";
    public ArenaQueryAttestation {
        if(!UUID.fromString(storeIdentity).toString().equals(storeIdentity)||snapshotGeneration<=0||resultCount!=8)throw new IllegalArgumentException("Exact complete eight-key arena query required");
        digest(indexIdentity);digest(snapshotRootIdentity);digest(definitionIdentity);digest(policyIdentity);digest(resultDigest);
        if(!ArenaDefinition.FIXED.identity().equals(definitionIdentity))throw new IllegalArgumentException("Unknown arena definition");
        if(layoutIdentity==null||layoutIdentity.isBlank()||layoutIdentity.length()>1024)throw new IllegalArgumentException("Bounded full arena layout identity required");
    }
    public String identity(){
        try{var bytes=new java.io.ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){write(out);}return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));}
        catch(IOException|java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    public boolean completeCatalogue(){return true;}
    public void verify(List<RollingCubeActor.CubeVersion> dependencies,String captureIdentity) {
        var entries=ArenaDefinition.FIXED.positions().stream().map(p->new ArenaReplacementIndex.Entry(p,
                dependencies.stream().filter(v->v.position().equals(p)).findFirst().orElseThrow().revision(),policyIdentity,captureIdentity)).toList();
        if(!resultDigest.equals(ArenaReplacementIndex.resultIdentity(entries)))throw new IllegalArgumentException("Arena query differs from complete union revisions/content");
    }
    public void write(DataOutputStream out)throws IOException{out.writeUTF(VERSION);out.writeUTF(storeIdentity);out.writeUTF(indexIdentity);out.writeLong(snapshotGeneration);out.writeUTF(snapshotRootIdentity);out.writeUTF(definitionIdentity);out.writeUTF(layoutIdentity);out.writeUTF(policyIdentity);out.writeUTF(resultDigest);out.writeInt(resultCount);}
    public static String policyIdentity(WorldIdentity world,String palette,RollingCubeActor.ReplacementPolicy policy,ArenaDefinition definition,String source,String layout){
        java.util.Objects.requireNonNull(world);java.util.Objects.requireNonNull(policy);java.util.Objects.requireNonNull(definition);digest(palette);
        if(source==null||source.isBlank()||source.length()>1024||layout==null||layout.isBlank()||layout.length()>1024)throw new IllegalArgumentException("Bounded exact source and layout required");
        try{var bytes=new java.io.ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){
            out.writeUTF("earth-arena-source-scope-v1");out.writeLong(world.seed());out.writeUTF(world.generatorId());out.writeUTF(world.generatorVersion());out.writeUTF(world.datasetVersion());out.writeUTF(world.blockPaletteVersion());
            out.writeUTF(palette);out.writeUTF(policy.providerIdentity());out.writeUTF(policy.materialPolicy());out.writeUTF(policy.detailPolicy());out.writeUTF(definition.identity());out.writeUTF(source);out.writeUTF(layout);
        }return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));}
        catch(IOException|java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    static void digest(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Exact SHA-256 identity required");}
}

package io.earthplatform.minecraft.fixture;

import com.google.gson.JsonObject;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.residency.SparseFacadeDiagnostics;
import io.earthplatform.minecraft.residency.SparseFacadeException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class NativeProbeChecks {
    public static final BlockPos LOWER_BLOCK = new BlockPos(8,-11000,8), UPPER_BLOCK = new BlockPos(8,8849,8);
    private NativeProbeChecks() {}
    public static WorldIdentity world(NativeSparseBinding binding) {
        return new WorldIdentity(0,"earthplatform:native-fixture","native-two-cube-v1","synthetic-two-cube-v1",
                "native-registry-v1/"+binding.palette().fingerprint());
    }
    public static void require(boolean condition,String message) { if(!condition)throw new IllegalStateException("Native sparse proof failed: "+message); }
    public static void rejects(Runnable operation,String name) {
        try { operation.run(); }
        catch(SparseFacadeException expected) { return; }
        throw new IllegalStateException("Native sparse proof failed: unsupported operation succeeded: "+name);
    }
    public static JsonObject observe(NativeSparseBinding binding,long epoch,String endpoint) {
        var level=binding.level();var source=level.getChunkSource();
        require(level.getMinBuildHeight()==-11008 && level.getHeight()==19872,"actual native level envelope");
        require(level.dimensionType().minY()==-11008 && level.dimensionType().height()==19872,"actual native dimension object envelope");
        require(binding.cubes().residentCount()==2,"two and only two resident owners");
        require(level.getBlockState(LOWER_BLOCK)==Blocks.GOLD_BLOCK.defaultBlockState(),"native Level lower read");
        require(level.getBlockState(UPPER_BLOCK)==Blocks.DIAMOND_BLOCK.defaultBlockState(),"native Level upper read");
        require(level.isLoaded(LOWER_BLOCK)&&level.isLoaded(UPPER_BLOCK),"native loaded queries");
        var facade=source.getChunk(0,0,ChunkStatus.FULL,false);
        require(facade==binding.facade(0,0,ChunkStatus.FULL),"native cache returns authoritative facade");
        var diagnostic=(SparseFacadeDiagnostics)facade;
        require(diagnostic.earthplatform$legacySectionSlots()==0 && diagnostic.earthplatform$legacyPostprocessingSlots()==0,"zero legacy height-sized arrays");
        rejects(()->level.getBlockState(new BlockPos(8,657,8)),"packed upper alias");
        rejects(()->level.getBlockState(new BlockPos(8,1288,8)),"packed lower alias");
        rejects(()->level.getChunk(1,0,ChunkStatus.FULL,false),"unknown column");
        rejects(level::getLightEngine,"unimplemented lighting");
        rejects(source::getLightEngine,"direct cache lighting bypass");
        require(source.getLoadedChunksCount()==0,"no vanilla cached columns or chunk holders");
        JsonObject report=new JsonObject();
        if(source instanceof NativeSparseCacheDiagnostics cache) {
            require(cache.earthplatform$emptySectionSlots()==0 && cache.earthplatform$emptyPostprocessingSlots()==0,"constructor EmptyLevelChunk has no height-sized arrays");
            report.addProperty("constructorEmptyChunkSectionSlots",cache.earthplatform$emptySectionSlots());
            report.addProperty("constructorEmptyChunkPostprocessingSlots",cache.earthplatform$emptyPostprocessingSlots());
        }
        report.addProperty("vanillaCachedColumnsOrHolders",source.getLoadedChunksCount());report.addProperty("epoch",epoch);report.addProperty("endpoint",endpoint);
        report.addProperty("scope","isolated actual native level in integrated client; data-only ownership, no rendered/player/ticking world");
        report.addProperty("levelClass",level.getClass().getName());report.addProperty("chunkSourceClass",source.getClass().getName());
        report.addProperty("minY",level.getMinBuildHeight());report.addProperty("height",level.getHeight());
        report.addProperty("residentCubes",binding.cubes().residentCount());report.addProperty("columnFacades",binding.facadeCount());
        report.addProperty("legacySectionSlots",diagnostic.earthplatform$legacySectionSlots());
        report.addProperty("legacyPostprocessingSlots",diagnostic.earthplatform$legacyPostprocessingSlots());
        report.addProperty("nativeLowerY",LOWER_BLOCK.getY());report.addProperty("nativeUpperY",UPPER_BLOCK.getY());
        report.addProperty("unknownAndAliasReadsRejected",true);return report;
    }
    public static void verifyLowerRetired(NativeSparseBinding binding) {
        require(binding.cubes().residentCount()==1,"retirement reduces residency to one");
        require(!binding.level().isLoaded(LOWER_BLOCK)&&binding.level().isLoaded(UPPER_BLOCK),"3D readiness after same-column retirement");
        rejects(()->binding.level().getBlockState(LOWER_BLOCK),"retired lower cube must not become air");
        require(binding.level().getBlockState(UPPER_BLOCK)==Blocks.DIAMOND_BLOCK.defaultBlockState(),"upper cube survives independently");
    }
    public static Path write(JsonObject report,String endpoint,long epoch) throws IOException {
        Path directory=Path.of("earthplatform-evidence");Files.createDirectories(directory);
        Path path=directory.resolve("native-sparse-probe-"+epoch+"-"+endpoint+".json");Files.writeString(path,report+"\n");return path.toAbsolutePath();
    }
}

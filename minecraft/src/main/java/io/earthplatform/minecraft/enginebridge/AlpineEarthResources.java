package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.geography.WorldManifest;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import net.neoforged.fml.loading.FMLPaths;

/** Setup-worker-only exact bundled data installation. No network, user-selected source or sampling I/O. */
final class AlpineEarthResources {
    private record FileSpec(String resource,String target,int bytes,String sha256) {}
    private static final List<FileSpec> FILES=List.of(
        new FileSpec("alpine_data/world.properties","terrain/world.properties",1358,"67f201204021520d5adad95e88c9da7a17a244f9f6f0a6bd20e56f321430f8b9"),
        new FileSpec("alpine_data/everest-expanded.egp","terrain/everest-expanded.egp",2162732,"597f435d639ea71f35d7b716b6a5f6b846e7dd22a8b8b13e7b12482e90927bca"),
        new FileSpec("worldcover_proof_data/landcover.properties","cover/landcover.properties",956,"16e23d166b4fa8847931b7c628a9eae5691f85ce99ec5793bed9c27d3210c25a"),
        new FileSpec("worldcover_proof_data/everest/landcover.elc","cover/everest/landcover.elc",1048620,"5276fb2adcf1c570e8328c126bc03a7c757cef2b8553f85a5a107b4a2608fdc7"),
        new FileSpec("worldcover_proof_data/marin-headlands/landcover.elc","cover/marin-headlands/landcover.elc",1048620,"82408a2d5707518f9d3deb69bc05645b1ea5674f935d3110653f830b88cfb27c"));
    private static EarthAlpineDefinition loaded;
    private static io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Reservation sourceReservation;
    private AlpineEarthResources() {}
    static synchronized EarthAlpineDefinition get() {
        if(loaded!=null)return loaded;
        sourceReservation=io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.global().reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.SOURCE_ASSETS,64L<<20);
        try{return loaded=installAndLoad(FMLPaths.GAMEDIR.get().toRealPath().resolve("earthplatform-everest-data-v1"));}
        catch(IOException e){sourceReservation.releaseAfterPhysicalDrain();sourceReservation=null;throw new IllegalStateException("Cannot load the checksum-pinned alpine Earth data",e);}
        catch(RuntimeException|Error e){sourceReservation.releaseAfterPhysicalDrain();sourceReservation=null;throw e;}
    }
    static EarthAlpineDefinition installAndLoad(Path root)throws IOException {
        root=root.toAbsolutePath().normalize();
        safeDirectory(root);
        for(var spec:FILES){
            byte[] bytes;
            try(var in=AlpineEarthResources.class.getResourceAsStream("/earthplatform/"+spec.resource())){
                if(in==null)throw new IOException("Missing bundled alpine source resource "+spec.resource());
                bytes=in.readNBytes(spec.bytes()+1);
            }
            if(bytes.length!=spec.bytes()||!WorldManifest.sha256(bytes).equals(spec.sha256()))
                throw new IOException("Bundled alpine resource differs from the pinned data: "+spec.resource());
            Path target=root.resolve(spec.target()).normalize();
            if(!target.startsWith(root))throw new IOException("Invalid alpine installation path");
            Path relative=root.relativize(target.getParent()),directory=root;
            for(Path component:relative){directory=directory.resolve(component);safeDirectory(directory);}
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Nonregular alpine destination "+spec.target());
            if(Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)&&Files.size(target)==bytes.length
                    &&WorldManifest.sha256(readBounded(target,spec.bytes())).equals(spec.sha256()))continue;
            Path temporary=Files.createTempFile(target.getParent(),".alpine-",".tmp");
            try{Files.write(temporary,bytes);Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            finally{Files.deleteIfExists(temporary);}
        }
        return EarthAlpineDefinition.loadPaths(root.resolve("terrain/world.properties"),root.resolve("cover/landcover.properties"),root.resolve("cover/everest/landcover.elc"));
    }
    private static byte[] readBounded(Path path,int maximum)throws IOException {
        try(var in=Files.newInputStream(path)){byte[] bytes=in.readNBytes(maximum+1);if(bytes.length>maximum)throw new IOException("Alpine data grew beyond its fixed bound");return bytes;}
    }
    private static void safeDirectory(Path directory)throws IOException {
        if(Files.exists(directory,LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("Alpine data directory is not a plain directory");
        }else Files.createDirectory(directory);
    }
}

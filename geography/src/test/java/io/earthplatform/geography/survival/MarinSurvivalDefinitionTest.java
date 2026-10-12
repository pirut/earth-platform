package io.earthplatform.geography.survival;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.*;
import io.earthplatform.geography.landcover.LandCoverClass;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/** Offline source, full-volume, feature-seam and downstream-edit contract; no Minecraft classes or JVM registry IDs. */
public final class MarinSurvivalDefinitionTest {
    private static long checks;
    private MarinSurvivalDefinitionTest() {}
    private static void check(boolean condition,String message) { checks++; if(!condition) throw new AssertionError(message); }
    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("Expected explicit rejection"); }
        catch(IllegalArgumentException | MissingTerrainException expected) { checks++; }
    }
    private static String digest(RegisteredBlockState[] cells) {
        var text=new StringBuilder(); for(var state:cells) text.append(state.canonical()).append('\n');
        return WorldManifest.sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }
    /** Deliberately downstream: an explicit AIR entry is an edit, not an absent entry. */
    private static RegisteredBlockState[] applyEdits(RegisteredBlockState[] generated,Map<Integer,RegisteredBlockState> edits) {
        var result=generated.clone(); edits.forEach((index,state) -> { CubePos.checkIndex(index); result[index]=state; }); return result;
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);
        var d=MarinSurvivalDefinition.load(root); var repeat=MarinSurvivalDefinition.load(root);
        var envelope=d.envelope(); var anchor=d.anchor();
        check(d.identity().equals(repeat.identity()),"identical source and generator identity on reload");
        check(d.sourceIdentity().equals(MarinSurvivalDefinition.SOURCE_IDENTITY),"source identity pin");
        check(d.generatorIdentity().equals(MarinSurvivalDefinition.GENERATOR_IDENTITY),"procedural identity separate from source");
        check(!d.generatorIdentity().equals(d.sourceIdentity()),"source and resources cannot share identity");
        check(d.sourceManifest().fingerprint().equals(MarinSurvivalDefinition.TERRAIN_MANIFEST_FINGERPRINT),"full manifest source hash");
        check(d.sourceManifest().provenance().resolutionArcSeconds()==15,"NOAA coarse 15 arcsecond macro, not metre observations");
        check(d.sourceManifest().provenance().verticalDatum().contains("EPSG:3855"),"unshifted EGM2008 datum");
        check(MarinSurvivalDefinition.ORE_POLICY.contains("disabled"),"ore policy reserved and disabled");
        check(MarinSurvivalDefinition.NOAA_ATTRIBUTION.contains("10.25921/fd45-gt74"),"NOAA citation retained");
        check(MarinSurvivalDefinition.WORLDCOVER_ATTRIBUTION.contains("CC-BY-4.0"),"WorldCover attribution and license retained");

        var properties=new TreeMap<>(Map.of("waterlogged","false","persistent","false","distance","2"));
        var leaves=new RegisteredBlockState("minecraft:oak_leaves",properties); properties.put("distance","7");
        check(leaves.canonical().equals("minecraft:oak_leaves[distance=2,persistent=false,waterlogged=false]"),"stable sorted properties and defensive copy");
        try { leaves.properties().put("distance","7"); throw new AssertionError("Mutable descriptor"); }
        catch(UnsupportedOperationException expected) { checks++; }
        check(leaves.equals(SurvivalMaterials.oakLeaves(2)),"same registered state independent of map order");
        rejects(() -> RegisteredBlockState.of("oak_log"));
        rejects(() -> RegisteredBlockState.of("minecraft:oak_log",Map.of("axis", "not valid")));
        rejects(() -> SurvivalMaterials.oakLeaves(0));
        check(SurvivalMaterials.supportedStates().containsAll(List.of(SurvivalMaterials.OAK_LOG,SurvivalMaterials.OAK_PLANKS,
                SurvivalMaterials.COBBLESTONE,SurvivalMaterials.CRAFTING_TABLE)),"native resource loop descriptors present");

        var point=d.projection().blockCentre(anchor.x(),anchor.z()); var raw=OfflineTerrain.load(root.resolve("data/noaa-etopo2022-proof/world-global.properties"));
        check(Math.abs(point.latitude()-37.847707261113165)<1e-12,"exact negative-Z geographic conversion");
        check(Math.abs(point.longitude()+122.53071230203825)<1e-12,"exact negative-X geographic conversion");
        check(Math.abs(d.column(anchor.x(),anchor.z()).sourceMetres()-134.06109259584335)<1e-10,"exact retained NOAA interpolation at anchor");
        check(anchor.surfaceY()==134 && anchor.feetY()==135,"original true-height spawn");
        check(d.stateAt(anchor.x(),134,anchor.z()).equals(SurvivalMaterials.GRASS),"natural support");
        check(d.stateAt(anchor.x(),135,anchor.z()).isAir() && d.stateAt(anchor.x(),136,anchor.z()).isAir(),"natural player-body clearance");
        check(d.surfaceY(anchor.x(),anchor.z()-1)==133,"nearby slope retained; no artificial shelf");
        check(envelope.maxTravelXExclusive()-envelope.minTravelX()==512 && envelope.maxTravelZExclusive()-envelope.minTravelZ()==512,"strict 512m playable footprint");
        check(envelope.minFeatureAnchorX()==envelope.minTravelX()-2 && envelope.maxFeatureAnchorZExclusive()==envelope.maxTravelZExclusive()+2,"explicit feature halo");
        check(envelope.minColumnX()<envelope.minFeatureAnchorX(),"far support distinct from mutable generation");
        check(MarinSurvivalDefinition.snapCubeCenter(-1)==0 && MarinSurvivalDefinition.snapCubeCenter(-5)==-128,"negative snapping and ties");

        long treeColumns=0,grassColumns=0; double minimum=Double.POSITIVE_INFINITY,maximum=Double.NEGATIVE_INFINITY;
        for(long z=envelope.minTravelZ();z<envelope.maxTravelZExclusive();z++) for(long x=envelope.minTravelX();x<envelope.maxTravelXExclusive();x++) {
            var column=d.column(x,z);
            if(column.sourceClassification().classification()==LandCoverClass.TREE_COVER) treeColumns++;
            else if(column.sourceClassification().classification()==LandCoverClass.GRASSLAND) grassColumns++;
            else throw new AssertionError("Unexpected source class inside pinned playable land");
            check(column.proceduralDeltaMetres()==0 && column.generatedMetres()==column.sourceMetres(),"no invented measured height or relief");
            check(column.surfaceY()==(long)StrictMath.floor(column.sourceMetres()),"floor source metres without Y offset");
            minimum=Math.min(minimum,column.sourceMetres()); maximum=Math.max(maximum,column.sourceMetres());
            if(Math.floorMod(x,31)==0 && Math.floorMod(z,29)==0)
                check(Double.doubleToLongBits(column.sourceMetres())==Double.doubleToLongBits(raw.elevationMetres(x,z)),"original NOAA data agree exactly");
        }
        check(treeColumns==174362 && grassColumns==87782,"exact full playable WorldCover census");
        for(long z:new long[]{envelope.minColumnZ(),envelope.maxColumnZExclusive()-128})
            for(long x:new long[]{envelope.minColumnX(),envelope.maxColumnXExclusive()-128}) check(d.knownFootprint(x,z,128),"far boundary including source halo fully supported");
        check(!d.knownFootprint(envelope.minColumnX()-1,envelope.minColumnZ(),1),"outside source footprint unavailable");
        check(!d.knownFootprint(Long.MAX_VALUE,0,128),"footprint overflow fails closed");
        rejects(() -> d.knownFootprint(anchor.x(),anchor.z(),129));
        rejects(() -> d.column(envelope.maxColumnXExclusive(),anchor.z()));
        rejects(() -> d.column(Long.MIN_VALUE,anchor.z()));
        rejects(() -> d.stateAt(envelope.minTravelX()-1,134,anchor.z()));
        rejects(() -> d.stateAt(anchor.x(),MarinSurvivalDefinition.MIN_BLOCK_Y-1,anchor.z()));
        rejects(() -> d.stateAt(anchor.x(),MarinSurvivalDefinition.MAX_BLOCK_Y_EXCLUSIVE,anchor.z()));
        rejects(() -> d.generateCube(new CubePos(envelope.maxCenterXExclusive(),8,anchor.center().z())));
        rejects(() -> d.generateCube(new CubePos(anchor.center().x(),envelope.maxCenterYExclusive(),anchor.center().z())));
        rejects(() -> d.treesIntersecting(Long.MIN_VALUE,0,Long.MAX_VALUE,1));
        check(d.column(envelope.minTravelX()-1,anchor.z()).sourceClassification().status()!=null,"far readable while corresponding generation is forbidden");

        var allTrees=d.treesIntersecting(envelope.minTravelX(),envelope.minTravelZ(),envelope.maxTravelXExclusive(),envelope.maxTravelZExclusive());
        check(!allTrees.isEmpty(),"natural source-conditioned procedural trees exist");
        var nearest=allTrees.stream().min(Comparator.comparingDouble(tree -> Math.hypot(tree.x()-anchor.x(),tree.z()-anchor.z()))).orElseThrow();
        check(Math.hypot(nearest.x()-anchor.x(),nearest.z()-anchor.z())<16,"harvestable natural tree within 16m, no supplied items");
        for(var tree:allTrees) {
            check(d.column(tree.x(),tree.z()).sourceClassification().classification()==LandCoverClass.TREE_COVER,"tree roots require observed cover10, positions remain procedural");
            check(tree.groundY()==d.surfaceY(tree.x(),tree.z()),"tree roots anchored to unchanged surface");
            check(tree.trunkHeight()>=4 && tree.trunkHeight()<=6,"bounded deterministic trunk height");
        }
        var xSeam=allTrees.stream().filter(t -> CubePos.local(t.x())<=1 || CubePos.local(t.x())>=14)
                .filter(t -> t.x()>envelope.minTravelX()+16 && t.x()<envelope.maxTravelXExclusive()-16).findFirst().orElseThrow();
        var zSeam=allTrees.stream().filter(t -> CubePos.local(t.z())<=1 || CubePos.local(t.z())>=14)
                .filter(t -> t.z()>envelope.minTravelZ()+16 && t.z()<envelope.maxTravelZExclusive()-16).findFirst().orElseThrow();
        var ySeam=allTrees.stream().filter(t -> CubePos.section(t.groundY()+1)!=CubePos.section(t.topY()+1))
                .filter(t -> t.x()>envelope.minTravelX()+16 && t.x()<envelope.maxTravelXExclusive()-16 && t.z()>envelope.minTravelZ()+16 && t.z()<envelope.maxTravelZExclusive()-16)
                .findFirst().orElseThrow();
        var cubeSet=new LinkedHashSet<CubePos>(); cubeSet.add(anchor.center());
        for(var tree:List.of(nearest,xSeam,zSeam,ySeam)) {
            for(long y=tree.groundY()+1;y<=tree.topY()+1;y++) for(long z=tree.z()-2;z<=tree.z()+2;z++) for(long x=tree.x()-2;x<=tree.x()+2;x++) {
                var pos=CubePos.fromBlock(x,y,z); if(d.permitsCenter(pos)) cubeSet.add(pos);
            }
        }
        cubeSet.add(CubePos.fromBlock(anchor.x(),-9600,anchor.z()));
        cubeSet.add(CubePos.fromBlock(anchor.x(),8000,anchor.z()));
        Map<CubePos,String> hashes=new HashMap<>(); int generatedLogs=0,generatedLeaves=0;
        for(var pos:cubeSet) {
            var cells=d.generateCube(pos); check(cells.length==4096,"full independent cubic payload");
            for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
                var state=cells[CubePos.index(x,y,z)];
                check(state!=null && state.equals(d.stateAt(pos.minBlockX()+x,pos.minBlockY()+y,pos.minBlockZ()+z)),"all 4096 cells equal independent point evaluation");
                check(!List.of(SurvivalMaterials.OAK_PLANKS,SurvivalMaterials.COBBLESTONE,SurvivalMaterials.CRAFTING_TABLE).contains(state),"craft outputs never generated as starter resources");
                if(state.equals(SurvivalMaterials.OAK_LOG)) generatedLogs++;
                if(state.registryId().equals("minecraft:oak_leaves")) generatedLeaves++;
                if(pos.minBlockY()==-9600) check(state.equals(SurvivalMaterials.STONE),"signed deep cubic stone, no low-Y substitution");
                if(pos.minBlockY()==8000) check(state.isAir(),"independent high-Y air");
            }
            hashes.put(pos,digest(cells));
        }
        check(generatedLogs>0 && generatedLeaves>0,"native logs and leaves in full cubic payloads");
        var reverse=new ArrayList<>(cubeSet); Collections.reverse(reverse);
        for(var pos:reverse) check(hashes.get(pos).equals(digest(repeat.generateCube(pos))),"cube load order/reload do not affect features or seams");
        try(var pool=Executors.newFixedThreadPool(3)) {
            var jobs=new ArrayList<Callable<Boolean>>();
            for(var pos:reverse) jobs.add(() -> hashes.get(pos).equals(digest(d.generateCube(pos))));
            for(var result:pool.invokeAll(jobs)) check(result.get(),"parallel generation independent of shared mutable state");
        }
        for(var tree:List.of(xSeam,zSeam,ySeam)) {
            for(long y=tree.groundY()+1;y<=tree.topY()+1;y++) for(long z=tree.z()-2;z<=tree.z()+2;z++) for(long x=tree.x()-2;x<=tree.x()+2;x++) {
                if(!envelope.containsPlayableColumn(x,z)) continue;
                var feature=tree.stateAt(x,y,z);
                if(feature.isAir() || y<=d.surfaceY(x,z)) continue;
                var pos=CubePos.fromBlock(x,y,z); var generated=d.generateCube(pos);
                check(generated[CubePos.indexOfBlock(x,y,z)].equals(feature),"cross-X/Y/Z cube feature fragment exact, including negative coordinates");
            }
        }
        var logPosition=CubePos.fromBlock(nearest.x(),nearest.groundY()+1,nearest.z());
        int logIndex=CubePos.indexOfBlock(nearest.x(),nearest.groundY()+1,nearest.z());
        var base=d.generateCube(logPosition); check(base[logIndex].equals(SurvivalMaterials.OAK_LOG),"natural log for downstream removal");
        var removed=applyEdits(base,Map.of(logIndex,SurvivalMaterials.AIR));
        check(removed[logIndex].isAir(),"explicit AIR edit wins over resource generation");
        check(applyEdits(repeat.generateCube(logPosition),Map.of(logIndex,SurvivalMaterials.AIR))[logIndex].isAir(),"AIR tombstone wins after regeneration/reload");
        check(applyEdits(base,Map.of(logIndex,SurvivalMaterials.CRAFTING_TABLE))[logIndex].equals(SurvivalMaterials.CRAFTING_TABLE),"player placed registered state is downstream override");
        base[logIndex]=SurvivalMaterials.AIR;
        check(d.generateCube(logPosition)[logIndex].equals(SurvivalMaterials.OAK_LOG),"caller-owned cube arrays cannot mutate generator");
        check(Arrays.equals(d.generateCube(anchor.center()),repeat.generateCube(anchor.center())),"fresh immutable generation remains exact");

        // Preparation pins every source input. Afterwards source/generation queries need no filesystem.
        Path isolated=Files.createTempDirectory("marin-survival-source-");
        var relativeFiles=List.of("data/noaa-etopo2022-proof/world-global.properties",
                "data/noaa-etopo2022-proof/regions/san-francisco/san-francisco-west.egp",
                "data/noaa-etopo2022-proof/regions/san-francisco/san-francisco-east.egp",
                "data/noaa-etopo2022-proof/regions/everest/everest.egp",
                "data/noaa-etopo2022-proof/regions/challenger-deep/challenger-deep.egp",
                "data/worldcover-2021-proof/landcover.properties","data/worldcover-2021-proof/marin-headlands/landcover.elc",
                "data/worldcover-2021-proof/everest/landcover.elc");
        try {
            for(String name:relativeFiles) { Path destination=isolated.resolve(name); Files.createDirectories(destination.getParent()); Files.copy(root.resolve(name),destination); }
            var loaded=MarinSurvivalDefinition.load(isolated);
            Path manifest=isolated.resolve(relativeFiles.getFirst()); String original=Files.readString(manifest);
            Files.writeString(manifest,original.replace("projection.originLat=0","projection.originLat=1"));
            try { MarinSurvivalDefinition.load(isolated); throw new AssertionError("Changed projection must fail pin"); } catch(IOException expected) { checks++; }
            Files.writeString(manifest,original);
            Path raster=isolated.resolve(relativeFiles.get(1)); byte[] bytes=Files.readAllBytes(raster); bytes[100]^=1; Files.write(raster,bytes);
            try { MarinSurvivalDefinition.load(isolated); throw new AssertionError("Corrupt NOAA payload must fail pin"); } catch(IOException expected) { checks++; }
            for(String name:relativeFiles) Files.delete(isolated.resolve(name));
            check(Arrays.equals(loaded.generateCube(anchor.center()),d.generateCube(anchor.center())),"no runtime I/O or neighbour dependency");
        } finally {
            try(var paths=Files.walk(isolated)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
        System.out.println("MarinSurvivalDefinitionTest PASS checks="+checks+" testedFullCubes="+cubeSet.size()+" trees="+allTrees.size()
                +" treeCoverColumns="+treeColumns+" grasslandColumns="+grassColumns+" sourceMin="+minimum+" sourceMax="+maximum);
        System.out.println("ANCHOR "+anchor+" sourceMetres="+d.column(anchor.x(),anchor.z()).sourceMetres()+" nearestTree="+nearest);
        System.out.println("ENVELOPE "+envelope);
        System.out.println("SOURCE_IDENTITY "+d.sourceIdentity());
        System.out.println("GENERATOR_IDENTITY "+d.generatorIdentity());
        System.out.println("DEFINITION_IDENTITY "+d.identity());
        System.out.println("SEAM_TREES x="+xSeam+" z="+zSeam+" y="+ySeam);
    }
}

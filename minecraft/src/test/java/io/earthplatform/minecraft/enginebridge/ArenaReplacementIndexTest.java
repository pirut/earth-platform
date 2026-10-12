package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ArenaReplacementIndexTest {
    static final String POLICY="a".repeat(64),SUMMARY="b".repeat(64),NEXT="c".repeat(64);
    @TempDir Path dir;
    static List<ArenaReplacementIndex.Entry> values(CubePos changed,String summary){return ArenaDefinition.FIXED.positions().stream().map(p->new ArenaReplacementIndex.Entry(p,p.equals(changed)?1:0,POLICY,summary)).toList();}
    static List<ArenaReplacementIndex.Entry> read(ArenaReplacementIndex index){var page=index.next(index.beginQuery(ArenaReplacementIndex.Bounds.all(),8));assertEquals(ArenaReplacementIndex.Status.COMPLETE,page.status(),page.reason());assertEquals(8,page.completion().resultCount());return page.entries();}
    ArenaReplacementIndex initialized(Path path)throws Exception{Files.createDirectories(path);var index=ArenaReplacementIndex.open(path,POLICY);index.initializeSummaries(index.snapshot(),values(null,SUMMARY));return index;}
    @Test void separateEightKeyCapabilityRetainsLegacyTwoLimitAndAtomicUnion()throws Exception{
        assertEquals(2,PagedReplacementIndex.MAX_LIVE_MATCHES);assertEquals(8,ArenaReplacementIndex.MAX_LIVE_MATCHES);assertEquals(304,ArenaReplacementIndex.MAX_INTENT_BYTES);
        var index=initialized(dir);var before=index.snapshot();var clean=values(null,SUMMARY);assertEquals(clean,read(index));var changed=values(ArenaDefinition.FIXED.positions().get(4),NEXT);index.begin(before,changed);assertFalse(index.isCurrent(before));assertThrows(IOException.class,()->ArenaReplacementIndex.open(dir,POLICY));assertEquals(232,Files.size(dir.resolve(ArenaReplacementIndex.INTENT_FILE)));index.confirm();assertEquals(changed,read(index));assertEquals(1,read(index).stream().filter(e->e.revision()!=0).count());assertEquals(1,read(index).stream().map(ArenaReplacementIndex.Entry::summaryIdentity).distinct().count());assertEquals(changed,read(ArenaReplacementIndex.open(dir,POLICY)));assertFalse(index.isCurrent(before));
    }
    @Test void missingNinthForeignAndPartialUnionRefuseBeforeIntent()throws Exception{
        var index=initialized(dir);var before=index.snapshot();byte[] root=Files.readAllBytes(dir.resolve(ArenaReplacementIndex.ROOT_FILE));var next=values(ArenaDefinition.FIXED.origin(),NEXT);
        var ninth=new ArrayList<>(next);ninth.add(new ArenaReplacementIndex.Entry(new CubePos(2,8,0),0,POLICY,NEXT));ninth.sort(Comparator.comparing(ArenaReplacementIndex.Entry::position));
        var mixed=new ArrayList<>(next);mixed.set(1,new ArenaReplacementIndex.Entry(mixed.get(1).position(),0,POLICY,SUMMARY));
        for(var bad:List.of(next.subList(0,7),ninth,mixed,values(null,SUMMARY))){assertThrows(IOException.class,()->index.begin(before,bad));assertTrue(index.isCurrent(before));assertArrayEquals(root,Files.readAllBytes(dir.resolve(ArenaReplacementIndex.ROOT_FILE)));assertFalse(Files.exists(dir.resolve(ArenaReplacementIndex.INTENT_FILE)));}
    }
    @Test void completeQueryAndLedgerRejectMissingOrNinthKeysIncludingInvisible()throws Exception{
        for(int count:new int[]{7,9}){Path path=dir.resolve("size"+count);Files.createDirectories(path);var index=ArenaReplacementIndex.open(path,POLICY);var values=new ArrayList<>(values(null,SUMMARY));if(count==7)values.removeLast();else values.add(new ArenaReplacementIndex.Entry(new CubePos(1000,8,1000),0,POLICY,SUMMARY));values.sort(Comparator.comparing(ArenaReplacementIndex.Entry::position));index.seedCatalogueForTests(values.iterator());assertThrows(IOException.class,()->ArenaRevisionLedger.open(path,ArenaDefinition.FIXED,POLICY,"layout"));}
    }
    @Test void legacyStoreIsNeverReinterpretedAndCorruptPageRefuses()throws Exception{
        Path legacy=dir.resolve("legacy");Files.createDirectories(legacy);PagedReplacementIndex.open(legacy,POLICY);assertThrows(IOException.class,()->ArenaReplacementIndex.open(legacy,POLICY));
        var index=initialized(dir.resolve("arena"));Path page;try(var files=Files.list(dir.resolve("arena"))){page=files.filter(p->p.getFileName().toString().startsWith("arena-paged-v1-page-")).findFirst().orElseThrow();}byte[] bytes=Files.readAllBytes(page);bytes[bytes.length-1]^=1;Files.write(page,bytes);assertEquals(ArenaReplacementIndex.Status.REFUSED,index.next(index.beginQuery(ArenaReplacementIndex.Bounds.all(),8)).status());
    }
    @Test void allDurableCrashCutsEitherRefuseOrRecoverOneWholeGeneration()throws Exception{
        for(var cut:ArenaReplacementIndex.DurablePoint.values()){
            Path path=dir.resolve(cut.name());var index=initialized(path);var prior=values(null,SUMMARY);var next=values(ArenaDefinition.FIXED.origin(),NEXT);index.failureInjectorForTests((point,ordinal)->{if(point==cut)throw new IOException("Simulated crash at "+point);});
            assertThrows(IOException.class,()->{index.begin(index.snapshot(),next);index.confirm();},cut.name());
            assertFalse(index.isCurrent(new ArenaReplacementIndex.Snapshot("00000000-0000-0000-0000-000000000000","a".repeat(64),1,"b".repeat(64))));
            try{var reopened=ArenaReplacementIndex.open(path,POLICY);var found=read(reopened);assertTrue(found.equals(prior)||found.equals(next),"No mixed catalogue after "+cut);}catch(IOException refused){assertNotNull(refused.getMessage());}
        }
    }
}

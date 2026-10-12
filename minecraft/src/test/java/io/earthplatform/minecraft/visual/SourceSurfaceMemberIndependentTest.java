package io.earthplatform.minecraft.visual;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.lod.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.earthplatform.minecraft.visual.DistanceGeometryIndependentTest.*;

/** Independent 16 m member-selection oracles; no native publication/GPU authority is implied. */
class SourceSurfaceMemberIndependentTest {
    static int bit(long x,long y,long z){return (int)((Math.floorMod(y,64)/16)*16+(Math.floorMod(z,64)/16)*4+Math.floorMod(x,64)/16);}
    static boolean selected(Map<NativePageKey,Long> masks,long x,long y,long z){
        Long mask=masks.get(NativePageKey.containing(x,y,z));return mask!=null&&(mask&(1L<<bit(x,y,z)))!=0;
    }
    static Map<NativePageKey,Long> everySourceMember(EarthDistanceSurfacePlan p){var result=new TreeMap<NativePageKey,Long>();for(var page:p.pages())result.put(page.key(),page.memberMask());return result;}
    static Map<NativePageKey,Long> filtered(EarthDistanceSurfacePlan p,java.util.function.IntPredicate bits){
        var result=new TreeMap<NativePageKey,Long>();for(var page:p.pages()){long mask=0;for(int b=0;b<64;b++)if(bits.test(b)&&(page.memberMask()&(1L<<b))!=0)mask|=1L<<b;if(mask!=0)result.put(page.key(),mask);}return result;
    }
    static void selectCube(Map<NativePageKey,Long> masks,long cubeX,long cubeY,long cubeZ){
        long x=cubeX*16,y=cubeY*16,z=cubeZ*16;var key=NativePageKey.containing(x,y,z);masks.merge(key,1L<<bit(x,y,z),(a,b)->a|b);
    }
    @Test void signBit63OwnsExactlyTheNegativeSixteenMetreCube(){
        var p=plan(source(2,(x,z)->-1),null);var key=new NativePageKey(-1,-1,-1);
        var c=SourceSurfaceCoverage.restrictedMembers(p,Map.of(key,Long.MIN_VALUE));
        assertEquals(Long.MIN_VALUE,c.eligibleMemberMasks().get(key));assertEquals(Set.of(key),c.eligiblePages());
        assertEquals(List.of(new SourceSurfaceCoverage.SolidSpan(-16,0)),c.solidSpans(-1,-1));
        for(long z=-17;z<=0;z++)for(long x=-17;x<=0;x++)for(long y=-17;y<=0;y++)assertEquals(x>=-16&&x<0&&z>=-16&&z<0&&y>=-16&&y<0,c.covers(x,y,z));
        assertEquals(SourceSurfaceCoverage.Classification.FULL,c.classification(-16,-16,0,0));
        assertEquals(SourceSurfaceCoverage.Classification.BOUNDARY,c.classification(-17,-16,0,0));
        assertEquals(SourceSurfaceCoverage.Classification.UNCOVERED,c.classification(0,-16,16,0));
    }
    @Test void signedMembersCoalesceOnlyAcrossSelectedContiguousSolidIntervals(){
        var s=source(2,CLIFF);var edited=capture(s,new CubePos(0,-1,-1)).withMaterial(0,-1,-1,0).compact().withMaterial(0,-2,-1,0).compact();var p=plan(s,edited);
        var masks=new TreeMap<NativePageKey,Long>();for(long cy:new long[]{-5,-1,0,1,3,11,12})selectCube(masks,0,cy,-1);
        var c=SourceSurfaceCoverage.restrictedMembers(p,masks);
        assertEquals(List.of(new SourceSurfaceCoverage.SolidSpan(-80,-64),new SourceSurfaceCoverage.SolidSpan(-16,-2),new SourceSurfaceCoverage.SolidSpan(0,32),new SourceSurfaceCoverage.SolidSpan(48,64),new SourceSurfaceCoverage.SolidSpan(176,201)),c.solidSpans(0,-1));
        for(long z:new long[]{-17,-16,-1,0})for(long x:new long[]{-1,0,15,16})for(long y=-130;y<220;y++){
            boolean expected=p.bounds().contains(x,z)&&selected(masks,x,y,z)&&oracleMaterial(s,edited,x,y,z)!=0;assertEquals(expected,c.covers(x,y,z));long yy=y;assertEquals(expected,c.solidSpans(x,z).stream().anyMatch(span->span.contains(yy)));
        }
        assertEquals(SourceSurfaceCoverage.Classification.BOUNDARY,c.classification(0,-1,1,0));
    }
    @Test void allColumnAndRectangleClassificationsMatchIndependentFaceMembership(){
        var s=source(2,CLIFF);var edited=capture(s,new CubePos(0,-1,0)).withMaterial(0,-1,0,0);var p=plan(s,edited);var faces=nativeFaces(p,p.pageIndex().keySet());
        for(int pattern=0;pattern<4;pattern++){
            int mode=pattern;var masks=filtered(p,b->mode==0?(b&1)==0:mode==1?(b&16)!=0:mode==2?b==63:(b%5)==0);
            var c=SourceSurfaceCoverage.restrictedMembers(p,masks);var columns=new HashMap<String,int[]>();
            for(var f:faces){var counts=columns.computeIfAbsent(f.x()+":"+f.z(),k->new int[2]);counts[0]++;if(selected(masks,f.x(),f.y(),f.z()))counts[1]++;}
            for(long z=-64;z<64;z++)for(long x=-64;x<64;x++){var n=columns.get(x+":"+z);assertEquals(n[1]==0?SourceSurfaceCoverage.Classification.UNCOVERED:n[0]==n[1]?SourceSurfaceCoverage.Classification.FULL:SourceSurfaceCoverage.Classification.BOUNDARY,c.classification(x,z,x+1,z+1));}
            var random=new Random(3100+pattern);for(int i=0;i<80;i++){
                int x0=random.nextInt(148)-74,z0=random.nextInt(148)-74,x1=x0+1+random.nextInt(30),z1=z0+1+random.nextInt(30);boolean any=false,full=true;
                for(int z=z0;z<z1;z++)for(int x=x0;x<x1;x++){var n=columns.get(x+":"+z);any|=n!=null&&n[1]>0;full&=n!=null&&n[0]==n[1];}
                assertEquals(full?SourceSurfaceCoverage.Classification.FULL:any?SourceSurfaceCoverage.Classification.BOUNDARY:SourceSurfaceCoverage.Classification.UNCOVERED,c.classification(x0,z0,x1,z1));
            }
        }
    }
    @Test void discontiguousSelectedMembersAndFarTopsHaveOneOwnerOverTheWholeSquare(){
        var p=plan(source(2,(x,z)->-1),null);var masks=filtered(p,b->((b&3)+((b>>>2)&3))%2==0);var c=SourceSurfaceCoverage.restrictedMembers(p,masks);
        var batch=data(c,new Sampler((x,z)->-1)).compose(null,null,CHECK);var rectangles=new ArrayList<Rect>();
        for(var entry:masks.entrySet())for(int b=0;b<64;b++)if((entry.getValue()&(1L<<b))!=0){var k=entry.getKey();long x=k.minX()+(b&3)*16L,z=k.minZ()+((b>>>2)&3)*16L;rectangles.add(new Rect(x,z,x+16,z+16));}
        for(var f:unpack(batch)){assertEquals(DhTerrainGeometry.Direction.UP,f.direction());assertEquals(0,f.y());rectangles.add(new Rect(f.x(),f.z(),f.x()+f.width(),f.z()+f.height()));}
        exactlyOneTopOwner(rectangles,-2048,2048);assertEquals(0,batch.unknownCells());
    }
    @Test void partialMembersLeaveEveryCliffHeightWithExactlyOneNativeOrFarFace(){
        var p=plan(source(2,CLIFF),null);var faces=nativeFaces(p,p.pageIndex().keySet());
        for(int pattern=0;pattern<3;pattern++){
            int mode=pattern;var masks=filtered(p,b->mode==0?((b>>>4)&1)==0:mode==1?((b>>>2)&1)!=0:b==0||b==63);var c=SourceSurfaceCoverage.restrictedMembers(p,masks);var batch=data(c,new Sampler(CLIFF)).compose(null,null,CHECK);int[][] count=new int[136][275];
            for(var f:faces)if(f.d()==EarthDistanceSurfacePlan.Direction.WEST&&f.x()==0&&f.z()>=-68&&f.z()<68&&f.y()>=-74&&f.y()<201&&selected(masks,f.x(),f.y(),f.z()))count[(int)f.z()+68][(int)f.y()+74]++;
            for(var f:unpack(batch))if(f.direction()==DhTerrainGeometry.Direction.WEST&&f.x()==0)for(long z=Math.max(-68,f.z());z<Math.min(68,f.z()+f.width());z++)for(long y=Math.max(-74,f.y());y<Math.min(201,f.y()+f.height());y++)count[(int)z+68][(int)y+74]++;
            for(int z=0;z<136;z++)for(int y=0;y<275;y++)assertEquals(1,count[z][y],"Member cliff hole/overlap z="+(z-68)+" y="+(y-74));
        }
    }
    @Test void allExposedMembersMatchLegacyClassificationWithoutClaimingHiddenInteriors(){
        var p=plan(source(2,(x,z)->-1),null);var legacy=coverage(p);var exact=SourceSurfaceCoverage.restrictedMembers(p,everySourceMember(p));
        assertEquals(legacy.classification(-64,-64,64,64),exact.classification(-64,-64,64,64));
        assertEquals(List.of(new SourceSurfaceCoverage.SolidSpan(-64,0)),legacy.solidSpans(-1,-1));assertEquals(List.of(new SourceSurfaceCoverage.SolidSpan(-16,0)),exact.solidSpans(-1,-1));
        for(var f:nativeFaces(p,p.pageIndex().keySet()))assertTrue(exact.covers(f.x(),f.y(),f.z()));
        assertEquals(data(legacy,new Sampler((x,z)->-1)).compose(null,null,CHECK).tiles(),data(exact,new Sampler((x,z)->-1)).compose(null,null,CHECK).tiles());
    }
    @Test void fullSixtyFourSourceMemberMaskIsOccupancyEquivalentToLegacyWholePage(){
        var p=plan(source(2,(x,z)->(x&1)==0?200:-1),null);var key=new NativePageKey(0,0,0);assertEquals(-1L,p.pageIndex().get(key).memberMask());
        var exact=SourceSurfaceCoverage.restrictedMembers(p,Map.of(key,-1L));var legacy=SourceSurfaceCoverage.restricted(p,Set.of(key));
        for(long z=-1;z<=64;z++)for(long x=-1;x<=64;x++){
            assertEquals(legacy.solidSpans(x,z),exact.solidSpans(x,z));assertEquals(legacy.classification(x,z,x+1,z+1),exact.classification(x,z,x+1,z+1));
            for(long y:new long[]{-1,0,15,16,31,32,47,48,63,64})assertEquals(legacy.covers(x,y,z),exact.covers(x,y,z));
        }
        assertNotEquals(legacy.identity(),exact.identity(),"Different occupancy API domains remain explicit even when one fixture is equal");
    }
    @Test void masksRejectZeroForeignAndNonSourceBitsAndAreImmutableSnapshots(){
        var p=plan(source(2,(x,z)->-1),null);var key=new NativePageKey(-1,-1,-1);var masks=new HashMap<NativePageKey,Long>();masks.put(key,Long.MIN_VALUE);
        var c=SourceSurfaceCoverage.restrictedMembers(p,masks);String identity=c.identity();masks.put(key,1L);masks.clear();assertEquals(Map.of(key,Long.MIN_VALUE),c.eligibleMemberMasks());assertEquals(identity,c.identity());
        assertThrows(UnsupportedOperationException.class,()->c.eligibleMemberMasks().clear());assertThrows(UnsupportedOperationException.class,()->c.eligiblePages().clear());
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->SourceSurfaceCoverage.restrictedMembers(p,Map.of(key,0L)));
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->SourceSurfaceCoverage.restrictedMembers(p,Map.of(new NativePageKey(100,100,100),1L)));
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->SourceSurfaceCoverage.restrictedMembers(p,Map.of(key,1L)));
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->SourceSurfaceCoverage.restrictedMembers(p,Map.of(key,-1L)));
        var empty=SourceSurfaceCoverage.restrictedMembers(p,Map.of());assertTrue(empty.eligiblePages().isEmpty());assertTrue(empty.solidSpans(0,0).isEmpty());assertEquals(SourceSurfaceCoverage.Classification.UNCOVERED,empty.classification(-64,-64,64,64));
        var nullValue=new HashMap<NativePageKey,Long>();nullValue.put(key,null);assertThrows(NullPointerException.class,()->SourceSurfaceCoverage.restrictedMembers(p,nullValue));
    }
    @Test void memberIdentityIsOrderAndFocusStableButMaskAndContentSensitive(){
        var s=source(2,(x,z)->-1);var p=plan(s,null);var masks=filtered(p,b->(b&1)==1);var c=SourceSurfaceCoverage.restrictedMembers(p,masks);
        var moved=plan(s,null,new CubePos(1,15,1),2);assertEquals(p.bounds(),moved.bounds());assertEquals(c.identity(),SourceSurfaceCoverage.restrictedMembers(moved,masks).identity());
        var reversed=new ArrayList<>(masks.entrySet());Collections.reverse(reversed);var reordered=new LinkedHashMap<NativePageKey,Long>();for(var entry:reversed)reordered.put(entry.getKey(),entry.getValue());assertEquals(c.identity(),SourceSurfaceCoverage.restrictedMembers(p,reordered).identity());
        var fewer=new HashMap<>(masks);var key=fewer.keySet().iterator().next();long mask=fewer.get(key);fewer.put(key,mask&~Long.lowestOneBit(mask));assertNotEquals(c.identity(),SourceSurfaceCoverage.restrictedMembers(p,fewer).identity());
        var changed=plan(source(2,(x,z)->-2),null);assertNotEquals(c.identity(),SourceSurfaceCoverage.restrictedMembers(changed,masks).identity());
        var edit=capture(s,new CubePos(0,-1,0)).withMaterial(1,-1,1,2);assertNotEquals(c.identity(),SourceSurfaceCoverage.restrictedMembers(plan(s,edit),masks).identity());
    }
    @Test void additionalMemberIndexBytesAreAdmittedBeforeReturningCoverage(){
        var s=source(2,(x,z)->-1);var p=plan(s,null);var caps=new EarthDistanceSurfacePlan.Limits(N.maxSourceColumns(),N.maxSourceBytes(),N.maxPlanColumns(),N.maxPages(),N.maxPieces(),N.maxQuads(),N.maxGeometryBytes(),p.coverageBytes());
        var tight=EarthDistanceSurfacePlan.build(s,null,p.center(),2,0,caps);assertEquals(p.coverageBytes(),tight.coverageBytes());
        assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->SourceSurfaceCoverage.restrictedMembers(tight,everySourceMember(tight)));
        var c=SourceSurfaceCoverage.restrictedMembers(p,everySourceMember(p));long slots=p.bounds().width()/64*(p.bounds().depth()/64);assertEquals(p.coverageBytes()+slots*32+c.eligiblePages().size()*96,c.coverageBytes());
    }
}

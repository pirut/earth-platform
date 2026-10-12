package io.earthplatform.minecraft.visual;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.earthplatform.minecraft.visual.EarthDistanceSurfacePlanTest.*;
class SourceSurfaceMemberCoverageTest {
 static Map<NativePageKey,Long> selected(EarthDistanceSurfacePlan p,int radius){
  var result=new TreeMap<NativePageKey,Long>();long min=-radius*16L,max=(radius+1)*16L;
  for(var page:p.pages()){long mask=0;for(int bit=0;bit<64;bit++)if((page.memberMask()&(1L<<bit))!=0){long x=page.key().minX()+(bit%4)*16,z=page.key().minZ()+((bit/4)%4)*16;if(x>=min&&x<max&&z>=min&&z<max)mask|=1L<<bit;}if(mask!=0)result.put(page.key(),mask);}return result;
 }
 @Test void chosenChunkColumnsDoNotClaimRoundedPhysicalGuardGeometry(){
  var p=plan(flatSource(2,0,-1),null,2,0);var c=SourceSurfaceCoverage.restrictedMembers(p,selected(p,2));
  assertEquals(SourceSurfaceCoverage.Classification.FULL,c.classification(-32,-32,48,48));
  assertEquals(SourceSurfaceCoverage.Classification.UNCOVERED,c.classification(48,0,64,16));
  assertEquals(SourceSurfaceCoverage.Classification.BOUNDARY,c.classification(47,0,49,1));
  assertEquals(List.of(new SourceSurfaceCoverage.SolidSpan(-16,0)),c.solidSpans(47,0));assertEquals(List.of(),c.solidSpans(48,0));
  assertTrue(c.covers(47,-1,0));assertFalse(c.covers(48,-1,0));assertFalse(c.covers(47,-17,0));
 }
 @Test void partialSignedSixteenMetreCliffBandIsNeitherExtrudedNorFull(){
  var p=plan(source(2,0,(x,z)->flat(x<0?-75:200)),null,2,0);var key=new NativePageKey(0,-1,0);long mask=1L<<48;
  assertNotEquals(0,p.pageIndex().get(key).memberMask()&mask);var c=SourceSurfaceCoverage.restrictedMembers(p,Map.of(key,mask));
  assertEquals(List.of(new SourceSurfaceCoverage.SolidSpan(-16,0)),c.solidSpans(0,0));assertTrue(c.covers(0,-16,0));assertFalse(c.covers(0,-17,0));assertFalse(c.covers(0,0,0));
  assertEquals(SourceSurfaceCoverage.Classification.BOUNDARY,c.classification(0,0,1,1));assertEquals(SourceSurfaceCoverage.Classification.UNCOVERED,c.classification(1,0,2,1));
 }
 @Test void highMaskBitAndImmutableIdentityAreExact(){
  var p=plan(flatSource(2,0,-1),null,2,0);var key=new NativePageKey(0,-1,0);var masks=new HashMap<NativePageKey,Long>();masks.put(key,Long.MIN_VALUE);
  var c=SourceSurfaceCoverage.restrictedMembers(p,masks);masks.clear();assertTrue(c.covers(63,-1,63));assertFalse(c.covers(47,-1,63));
  assertEquals(Long.MIN_VALUE,c.eligibleMemberMasks().get(key));assertThrows(UnsupportedOperationException.class,()->c.eligibleMemberMasks().clear());
  assertNotEquals(c.identity(),SourceSurfaceCoverage.restrictedMembers(p,Map.of(key,1L<<62)).identity());
 }
 @Test void malformedOrNonSourceMemberAuthorityIsRefused(){
  var p=plan(flatSource(2,0,-1),null,2,0);var key=new NativePageKey(0,-1,0);
  assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->SourceSurfaceCoverage.restrictedMembers(p,Map.of(key,0L)));
  assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->SourceSurfaceCoverage.restrictedMembers(p,Map.of(key,1L)));
  assertThrows(EarthDistanceSurfacePlan.Refusal.class,()->SourceSurfaceCoverage.restrictedMembers(p,Map.of(new NativePageKey(7,-1,0),Long.MIN_VALUE)));
  var empty=SourceSurfaceCoverage.restrictedMembers(p,Map.of());assertEquals(SourceSurfaceCoverage.Classification.UNCOVERED,empty.classification(-32,-32,48,48));assertEquals(List.of(),empty.solidSpans(0,0));
 }
 @Test void wholePageFactoryPreservesPriorGeometryAndCanonicalIdentity() throws Exception{
  var p=plan(flatSource(2,0,-1),null,2,0);var c=SourceSurfaceCoverage.complete(p,p.pageIndex().keySet());
  assertEquals(List.of(new SourceSurfaceCoverage.SolidSpan(-64,0)),c.solidSpans(0,0));
  var digest=MessageDigest.getInstance("SHA-256");digest.update(("source-surface-coverage-content-031-v2\n"+p.source().identity()+"\n"+p.captureIdentity()+"\n"+p.bounds()+"\n").getBytes(StandardCharsets.UTF_8));for(var k:new TreeSet<>(p.pageIndex().keySet()))digest.update((k+"\n"+p.pageIndex().get(k).identity()+"\n").getBytes(StandardCharsets.UTF_8));
  assertEquals(HexFormat.of().formatHex(digest.digest()),c.identity());assertEquals(c.identity(),SourceSurfaceCoverage.restricted(p,p.pageIndex().keySet()).identity());
 }
}

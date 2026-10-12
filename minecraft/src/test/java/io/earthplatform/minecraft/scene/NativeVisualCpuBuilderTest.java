package io.earthplatform.minecraft.scene;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure byte/ownership/selector tests ONLY. The synthetic corpus is not an actual native-renderer parity oracle. */
class NativeVisualCpuBuilderTest {
    private static final String STAMP = "0123456789abcdef".repeat(4);
    private static final NativeVisualCapsule CORPUS = syntheticCorpus();
    private static NativeVisualCapsule syntheticCorpus() {
        byte[] table = new byte[NativeVisualCapsule.TABLE_BYTES];
        ByteBuffer data = ByteBuffer.wrap(table).order(ByteOrder.nativeOrder());
        for (int variant=0;variant<8;variant++)for(int face=0;face<6;face++)for(int mask=0;mask<256;mask++) {
            int offset=((variant*6+face)*256+mask)*208;
            for(int vertex=0;vertex<4;vertex++) {
                int base=offset+vertex*52;
                for(int b=12;b<52;b++)table[base+b]=(byte)(variant*31+face*11+mask+b+vertex);
                data.putFloat(base,(vertex&1));data.putFloat(base+4,(vertex>>>1));data.putFloat(base+8,0);
            }
        }
        return new NativeVisualCapsule(1,STAMP,STAMP,STAMP,table);
    }
    private static NativeVisualCpuBuilder builder(byte[] bytes,int[] members) {
        return new NativeVisualCpuBuilder(new NativeVisualCpuBuilder.Input(0,0,0,bytes,members,STAMP),CORPUS,new NativeVisualCpuBuilder.Cancellation());
    }
    private static void state(byte[] bytes,int x,int y,int z,int code){bytes[NativeVisualCpuBuilder.Input.haloIndex(x,y,z)]=(byte)code;}
    private static byte[] read(NativeVisualCpuPiece piece){ByteBuffer out=ByteBuffer.allocate(piece.byteLength());piece.copyTo(out);return out.array();}
    @Test void constantsUseActual52BytePieceCapacity(){
        assertEquals(2555904,NativeVisualCapsule.TABLE_BYTES);assertEquals(5041,NativeVisualCpuPiece.MAX_QUADS);
        assertEquals(1048528,NativeVisualCpuPiece.CAPACITY_BYTES);assertTrue(NativeVisualCpuPiece.CAPACITY_BYTES<=1<<20);
        assertTrue((long)(5041+1)*208>1<<20);
    }
    @Test void selectorPreservesSignedJavaLegacyArithmeticAtAdversarialCoordinates(){
        int[][] positions={{0,0,0},{-1,-1,-1},{9700000,8863,-3100000},{9700000,-11008,-3100000},
                {Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE},{Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE}};
        Random points=new Random(1831);
        for(int i=0;i<100_000;i++) {
            int[] p=i<positions.length?positions[i]:new int[]{points.nextInt(),points.nextInt(),points.nextInt()};
            long mixed=(long)(p[0]*3129871)^(long)p[2]*116129781L^(long)p[1];mixed=mixed*mixed*42317861L+mixed*11L;long seed=mixed>>16;
            assertEquals(seed,NativeVisualCapsule.blockSeed(p[0],p[1],p[2]));
            assertEquals(Math.abs((int)new Random(seed).nextLong())%4,NativeVisualCapsule.stoneVariant(p[0],p[1],p[2]));
        }
    }
    @Test void onlyPositionBytesChangeAtEveryPageOffset(){
        byte[] base=new byte[208], translated=new byte[208];
        for(int variant=0;variant<8;variant++)for(int face=0;face<6;face++)for(int offset=0;offset<64;offset++) {
            CORPUS.appendFace(base,0,variant,face,137,0,0,0);
            CORPUS.appendFace(translated,0,variant,face,137,offset,63-offset,offset);
            ByteBuffer a=ByteBuffer.wrap(base).order(ByteOrder.nativeOrder()),b=ByteBuffer.wrap(translated).order(ByteOrder.nativeOrder());
            for(int v=0;v<4;v++) {
                assertEquals(a.getFloat(v*52)+offset,b.getFloat(v*52));
                assertEquals(a.getFloat(v*52+4)+63-offset,b.getFloat(v*52+4));
                assertEquals(a.getFloat(v*52+8)+offset,b.getFloat(v*52+8));
                for(int byteIndex=12;byteIndex<52;byteIndex++)assertEquals(base[v*52+byteIndex],translated[v*52+byteIndex]);
            }
        }
    }
    @Test void emptyCompletionCertifiesEveryRequestedMemberWithoutDummyVertices(){
        var worker=builder(new byte[NativeVisualCpuBuilder.HALO_BYTES],new int[]{0,1,63});var piece=worker.nextPiece();
        assertTrue(piece.completesRequest());assertEquals(0,piece.quads());assertEquals(0,piece.byteLength());
        assertEquals(3,piece.memberRanges().size());for(var range:piece.memberRanges()){assertEquals(0,range.firstFace());assertTrue(range.completesMember());}
        assertThrows(IllegalStateException.class,worker::nextPiece);
    }
    @Test void oneBlockAndEveryPaletteProduceSixSealedActualSizeQuads(){
        for(int palette=1;palette<=5;palette++){
            byte[] bytes=new byte[NativeVisualCpuBuilder.HALO_BYTES];state(bytes,5,7,9,palette);var piece=builder(bytes,new int[]{0}).nextPiece();
            assertEquals(6,piece.quads());assertEquals(24,piece.vertices());assertEquals(36,piece.indexCount());assertTrue(piece.completesRequest());
            assertEquals(NativeVisualCapsule.sha256(read(piece),piece.byteLength()),piece.bytesDigest());
            assertTrue(piece.retainedBytes()>=NativeVisualCpuPiece.CAPACITY_BYTES);
        }
    }
    @Test void exact5041QuadSplittingRetainsEveryScanCursorOnce(){
        byte[] bytes=new byte[NativeVisualCpuBuilder.HALO_BYTES];
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)if(((x+y+z)&1)==0)state(bytes,x,y,z,1);
        var worker=builder(bytes,new int[]{0});int expectedCursor=0,total=0,ordinal=0;var counts=new ArrayList<Integer>();
        while(true){var piece=worker.nextPiece();assertEquals(ordinal++,piece.ordinal());counts.add(piece.quads());
            for(var range:piece.memberRanges()){assertEquals(expectedCursor,range.firstFace());expectedCursor=range.endFace();}total+=piece.quads();
            assertTrue(piece.byteLength()<=1<<20);if(piece.completesRequest())break;}
        assertEquals(List.of(5041,5041,2206),counts);assertEquals(12288,total);assertEquals(4096*6,expectedCursor);
    }
    @Test void fullPageCullsSharedMemberFacesAndCompletes64MembersExactly(){
        byte[] bytes=new byte[NativeVisualCpuBuilder.HALO_BYTES];
        for(int y=0;y<64;y++)for(int z=0;z<64;z++)for(int x=0;x<64;x++)state(bytes,x,y,z,1);
        int[] members=new int[64];for(int i=0;i<64;i++)members[i]=i;
        int[] cursors=new int[64];var worker=builder(bytes,members);int quads=0;
        while(true){var piece=worker.nextPiece();quads+=piece.quads();for(var range:piece.memberRanges()){
            assertEquals(cursors[range.member()],range.firstFace());cursors[range.member()]=range.endFace();}
            if(piece.completesRequest())break;}
        assertEquals(6*64*64,quads);for(int cursor:cursors)assertEquals(4096*6,cursor);
    }
    @Test void inputsAreDeeplyFrozenAndOutputStorageNeverEscapes(){
        byte[] bytes=new byte[NativeVisualCpuBuilder.HALO_BYTES];state(bytes,1,1,1,5);int[] members={0};
        var input=new NativeVisualCpuBuilder.Input(0,0,0,bytes,members,STAMP);Arrays.fill(bytes,(byte)255);members[0]=63;
        var piece=new NativeVisualCpuBuilder(input,CORPUS,new NativeVisualCpuBuilder.Cancellation()).nextPiece();assertEquals(6,piece.quads());
        byte[] first=read(piece);Arrays.fill(first,(byte)0);assertFalse(Arrays.equals(first,read(piece)));
        assertThrows(UnsupportedOperationException.class,()->piece.memberRanges().clear());
        assertThrows(IllegalArgumentException.class,()->piece.copyTo(ByteBuffer.allocate(piece.byteLength()-1)));
    }
    @Test void unknownRequiredHaloFailsWithoutPretendingItIsAir(){
        byte[] bytes=new byte[NativeVisualCpuBuilder.HALO_BYTES];state(bytes,0,0,0,1);state(bytes,-1,0,0,255);
        var input=new NativeVisualCpuBuilder.Input(0,0,0,bytes,new int[]{0},STAMP);
        var worker=new NativeVisualCpuBuilder(input,CORPUS,new NativeVisualCpuBuilder.Cancellation());
        var failure=assertThrows(NativeVisualCpuBuilder.UnknownDependency.class,worker::nextPiece);
        assertTrue(failure.matches(input,-1,0,0));assertNull(failure.getCause());
        assertFalse(failure.matches(new NativeVisualCpuBuilder.Input(0,0,0,bytes,new int[]{0},STAMP),-1,0,0));
        assertFalse(failure.matches(input,0,0,0));
        assertThrows(IllegalStateException.class,worker::nextPiece);
    }
    @Test void cancellationBeforeAndBetweenPiecesIsTerminal(){
        byte[] bytes=new byte[NativeVisualCpuBuilder.HALO_BYTES];var cancel=new NativeVisualCpuBuilder.Cancellation();
        var first=new NativeVisualCpuBuilder(new NativeVisualCpuBuilder.Input(0,0,0,bytes,new int[]{0},STAMP),CORPUS,cancel);
        cancel.cancel();assertThrows(CancellationException.class,first::nextPiece);assertThrows(IllegalStateException.class,first::nextPiece);
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)if(((x+y+z)&1)==0)state(bytes,x,y,z,1);
        var later=new NativeVisualCpuBuilder.Cancellation();var worker=new NativeVisualCpuBuilder(new NativeVisualCpuBuilder.Input(0,0,0,bytes,new int[]{0},STAMP),CORPUS,later);
        var piece=worker.nextPiece();String digest=piece.bytesDigest();later.cancel();assertThrows(CancellationException.class,worker::nextPiece);
        assertEquals(digest,NativeVisualCapsule.sha256(read(piece),piece.byteLength()));
    }
    @Test void secondWriterCannotResumeAnExistingWorker()throws Exception{
        byte[] bytes=new byte[NativeVisualCpuBuilder.HALO_BYTES];for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)if(((x+y+z)&1)==0)state(bytes,x,y,z,1);
        var worker=builder(bytes,new int[]{0});worker.nextPiece();var failure=new AtomicReference<Throwable>();
        Thread other=new Thread(()->{try{worker.nextPiece();}catch(Throwable t){failure.set(t);}});other.start();other.join();assertInstanceOf(IllegalStateException.class,failure.get());
        assertDoesNotThrow(worker::nextPiece);
    }
    @Test void inputRejectsMisalignmentOverflowDuplicateMissingMembersAndUnsupportedStates(){
        byte[] bytes=new byte[NativeVisualCpuBuilder.HALO_BYTES];
        assertThrows(IllegalArgumentException.class,()->new NativeVisualCpuBuilder.Input(1,0,0,bytes,new int[]{0},STAMP));
        assertThrows(ArithmeticException.class,()->new NativeVisualCpuBuilder.Input(Integer.MAX_VALUE-63,0,0,bytes,new int[]{0},STAMP));
        assertThrows(ArithmeticException.class,()->new NativeVisualCpuBuilder.Input(Integer.MIN_VALUE,0,0,bytes,new int[]{0},STAMP));
        for(int[] members:new int[][]{{},{0,0},{1,0},{64},{-1}})assertThrows(IllegalArgumentException.class,()->new NativeVisualCpuBuilder.Input(0,0,0,bytes,members,STAMP));
        assertThrows(IllegalArgumentException.class,()->new NativeVisualCpuBuilder.Input(0,0,0,new byte[42],new int[]{0},STAMP));
        state(bytes,0,0,0,6);assertThrows(IllegalArgumentException.class,()->new NativeVisualCpuBuilder.Input(0,0,0,bytes,new int[]{0},STAMP));
    }
    @Test void workerBytecodeHasNoMinecraftIrisAtlasOrGpuDependency()throws Exception{
        for(Class<?> type:List.of(NativeVisualCapsule.class,NativeVisualCapsule.Identity.class,NativeVisualCpuBuilder.class,NativeVisualCpuBuilder.Input.class,
                NativeVisualCpuBuilder.Cancellation.class,NativeVisualCpuBuilder.UnknownDependency.class,NativeVisualCpuPiece.class,NativeVisualCpuPiece.MemberRange.class)) {
            try(InputStream in=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){
                assertNotNull(in);String bytes=new String(in.readAllBytes(),StandardCharsets.ISO_8859_1);
                for(String banned:List.of("net/minecraft/","net/irisshaders/","org/lwjgl/","com/mojang/","java/util/function/"))assertFalse(bytes.contains(banned),type+" retains "+banned);
            }
        }
    }
    /** Focused cached execution avoids any FML bootstrap and explicitly reports its limited scope. */
    public static void main(String[] args)throws Exception{
        int passed=0;var tests=new NativeVisualCpuBuilderTest();
        for(var method:NativeVisualCpuBuilderTest.class.getDeclaredMethods())if(method.isAnnotationPresent(Test.class)){
            try{method.invoke(tests);System.out.println("PASS "+method.getName());passed++;}
            catch(InvocationTargetException failure){throw new AssertionError(method.getName(),failure.getCause());}
        }
        System.out.println("Pure helper tests passed="+passed+"; actual transformed renderer parity NOT RUN");
    }
}

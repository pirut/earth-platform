package io.earthplatform.minecraft.scene;

import io.earthplatform.minecraft.visual.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.joml.Matrix4f;

/** Test-only modeled physical receipts. Real CPU scan and publication validation; NOT GPU acceptance. */
public final class BodyNativeCompletionFixture {
    private static final String HASH="0123456789abcdef".repeat(4);
    private static final NativeVisualCapsule CAPSULE=new NativeVisualCapsule(1,HASH,HASH,HASH,new byte[NativeVisualCapsule.TABLE_BYTES]);
    private BodyNativeCompletionFixture(){}
    public static String capsuleIdentity(){return CAPSULE.identity().bytesDigest();}
    public static NativeVisualPublication publication(EarthDistanceSurfacePlan plan,NativeVisualStamp stamp,BooleanSupplier live) {
        try{
            var receipts=new ArrayList<NativeVisualGpuPiece.Completion>();
            for(var page:plan.pages()){
                var key=page.key();byte[] halo=new byte[NativeVisualCpuBuilder.HALO_BYTES];
                for(int y=-1;y<=64;y++)for(int z=-1;z<=64;z++)for(int x=-1;x<=64;x++)
                    halo[NativeVisualCpuBuilder.Input.haloIndex(x,y,z)]=(byte)plan.materialAt(key.minX()+x,key.minY()+y,key.minZ()+z);
                int[] members=new int[Long.bitCount(page.memberMask())];int next=0;
                for(int i=0;i<64;i++)if((page.memberMask()&(1L<<i))!=0)members[next++]=i;
                var builder=new NativeVisualCpuBuilder(new NativeVisualCpuBuilder.Input(Math.toIntExact(key.minX()),Math.toIntExact(key.minY()),Math.toIntExact(key.minZ()),halo,members,page.identity()),CAPSULE,new NativeVisualCpuBuilder.Cancellation());
                while(true){var piece=builder.nextPiece();
                    var receipt=NativeVisualPublicationIndependentTest.part(piece.ordinal(),piece.quads(),piece.completesRequest(),piece.requestStamp(),piece.memberRanges().toArray(NativeVisualCpuPiece.MemberRange[]::new));
                    NativeVisualPublicationIndependentTest.set(receipt.physicalOwner(),"key",key);
                    NativeVisualPublicationIndependentTest.set(receipt.physicalOwner(),"capsuleIdentity",piece.capsuleIdentity());
                    receipts.add(receipt);if(piece.completesRequest())break;
                }
            }
            return new NativeVisualPublication(stamp,plan,receipts,new Matrix4f().perspective(1,1,.05f,stamp.effectiveDistanceChunks()*64f),NativeVisualPublication.cameraPermit(plan.center()),live);
        }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        catch(Exception failure){throw new AssertionError(failure);}
    }
}

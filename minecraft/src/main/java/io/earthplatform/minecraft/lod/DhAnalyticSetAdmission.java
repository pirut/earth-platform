package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.NativeReplacementCapture;
import io.earthplatform.minecraft.enginebridge.ReplacementWorkspaces;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Pure authoritative pre-save feasibility. It grants no edit, lease, save or publication authority. */
public final class DhAnalyticSetAdmission {
    public static final String VERSION="dh-two-workspace-presave-capacity-v2";
    public record Receipt(boolean admitted,String reason,String contentIdentity,DhReplacementSetComposition.Census census) { }
    private DhAnalyticSetAdmission() { }

    /**
     * Reads at most two immutable 20-cubed palette payloads. No provider callbacks, GL,
     * client objects, face allocation or save is performed. Captures include clean regions.
     * The actor must bind the resulting identity to its exact prior durable set and transaction.
     */
    public static Receipt assess(DhAnalyticFlatPolicy policy,ReplacementWorkspaces workspaces,
                                 List<NativeReplacementCapture> canonicalCompleteTwo,Runnable cancellation) {
        Objects.requireNonNull(policy);Objects.requireNonNull(workspaces);Objects.requireNonNull(cancellation).run();
        Objects.requireNonNull(canonicalCompleteTwo);
        if(canonicalCompleteTwo.size()!=2)return refused("Exactly two complete captures required", "");
        var captures=List.copyOf(canonicalCompleteTwo);
        if(!workspaces.original().equals(new CubePos(0,Math.toIntExact(Math.floorDiv(policy.surfaceY(),16)),0)))
            return refused("Workspace policy lies outside the exhaustive analytic proof", "");
        var immutable=new ArrayList<DhVoxelReplacementGeometry.Capture>(2);
        StringBuilder content=new StringBuilder(VERSION).append('\n').append(policy.identity()).append('\n')
                .append(workspaces.identity()).append('\n');
        long bytes=0;
        for(int i=0;i<2;i++){
            cancellation.run();var capture=Objects.requireNonNull(captures.get(i));
            if(!capture.position().equals(workspaces.positions().get(i)))return refused("Canonical complete workspace order required", "");
            content.append(capture.contentIdentity()).append('\n');
            bytes=Math.addExact(bytes,capture.declaredBytes());
            if(bytes>16000)return refused("Two compact captures exceed their retained payload bound", "");
            immutable.add(DhVoxelReplacementGeometry.captureCompact(capture));
        }
        String identity=hash(content.toString());
        final DhReplacementSetComposition.Census census;
        // One complete-set validator checks overlap equality and flat support outside the
        // UNION of editable cubes. A legitimate neighboring edit may lie in both read halos.
        try{census=DhReplacementSetComposition.inspectCaptures(policy,immutable,cancellation);}
        catch(DhReplacementComposition.Declined declined){return refused(declined.getMessage(),identity);}
        cancellation.run();
        if(!census.fits())return new Receipt(false,"Prospective replacement set exceeds the fixed pooled face/owner/byte bounds",identity,census);
        // Any two admitted publications fit the old/new physical pool even with no reuse.
        long unionBytes=Math.multiplyExact(2L*88,DhAdaptiveTerrainData.MAX_FACES);
        if(2*DhAdaptiveTerrainData.MAX_TILES>128||unionBytes>8L*1024*1024
                ||4*DhReplacementComposition.MAX_RESULT_BYTES>20L*1024*1024)
            throw new AssertionError("Fixed publication-union/cache proof no longer matches pool caps");
        return new Receipt(true,"",identity,census);
    }
    private static Receipt refused(String reason,String identity){return new Receipt(false,reason,identity,null);}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}

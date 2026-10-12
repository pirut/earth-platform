package io.earthplatform.compat.dh;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import org.joml.Matrix4f;

/** Copies the installed event's Minecraft matrices. Never uses DH's altered projection/world offset. */
public final class InstalledDhFrame {
    private InstalledDhFrame(){}
    public record Inputs(Matrix4f projection,Matrix4f modelView,float partialTicks) { }
    public static Inputs capture(Object transientContext){
        if(!(transientContext instanceof DhApiRenderParam p))throw new IllegalArgumentException("Installed DH render event context required");
        // Upstream matrices have row/column naming opposite to JOML. Use its pinned converter.
        Matrix4f projection=DhMat4f.createJomlMatrix(p.mcProjectionMatrix),view=DhMat4f.createJomlMatrix(p.mcModelViewMatrix);
        if(!projection.isFinite()||!view.isFinite()||!Float.isFinite(p.partialTicks))throw new IllegalArgumentException("Finite current render matrices required");
        return new Inputs(projection,view,p.partialTicks);
    }
}

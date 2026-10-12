package io.earthplatform.minecraft.coregate;

import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.enginebridge.NativeAlpineCubeProvider;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.util.Objects;

/** Setup-only immutable real-source preparation, invoked on the existing bounded workers. */
final class RollingAlpinePreparation {
    private RollingAlpinePreparation() {}
    record SourceEvidence(String definitionIdentity,String sourceIdentity,String detailIdentity,String materialIdentity,
                          double measuredMetres,double proceduralDeltaMetres,double generatedMetres,long surfaceY,EarthAlpineDefinition definition) {
        SourceEvidence {
            Objects.requireNonNull(definition);if(!definition.identity().equals(definitionIdentity))throw new IllegalArgumentException("Earth source evidence/definition mismatch");
            for(String value:new String[]{definitionIdentity,sourceIdentity,detailIdentity,materialIdentity})
                if(value==null||!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Pinned Earth digest required");
            if(!Double.isFinite(measuredMetres)||!Double.isFinite(proceduralDeltaMetres)||!Double.isFinite(generatedMetres)
                    ||Math.abs(proceduralDeltaMetres)>2||generatedMetres!=measuredMetres+proceduralDeltaMetres
                    ||surfaceY!=(long)Math.floor(generatedMetres))throw new IllegalArgumentException("Invalid source/detail height evidence");
        }
        com.google.gson.JsonObject json() {
            var j=new com.google.gson.JsonObject();
            j.addProperty("definitionIdentity",definitionIdentity);j.addProperty("sourceIdentity",sourceIdentity);
            j.addProperty("detailIdentity",detailIdentity);j.addProperty("materialIdentity",materialIdentity);
            j.addProperty("measuredMetres",measuredMetres);j.addProperty("proceduralDeltaMetres",proceduralDeltaMetres);
            j.addProperty("generatedMetres",generatedMetres);j.addProperty("surfaceY",surfaceY);return j;
        }
    }
    static RollingEarthPreparation.Prepared prepare(EnginePaletteContext palette,Runnable cancellationCheck) {
        Objects.requireNonNull(palette);Objects.requireNonNull(cancellationCheck);
        long start=System.nanoTime();cancellationCheck.run();
        var earth=NativeAlpineCubeProvider.prepare(palette);cancellationCheck.run();
        var source=earth.definition();var anchor=source.anchor();
        var provider=RollingGateFixture.wrap(earth,earth.identity());
        var sampler=earth.surfaceSampler();
        int ground=Math.toIntExact(anchor.surfaceY());
        if(sampler.surfaceY(anchor.x(),anchor.z())!=ground)
            throw new IllegalStateException("Prepared Earth anchor disagrees with the exact native/far source");
        var column=source.column(anchor.x(),anchor.z());
        var evidence=new SourceEvidence(source.identity(),source.sourceIdentity(),source.detailIdentity(),source.materialPolicyIdentity(),column.sourceMetres(),column.proceduralDeltaMetres(),column.generatedMetres(),column.surfaceY(),source);
        var descriptor=RollingSessionDescriptor.alpine(provider.identity(),palette,anchor.x(),ground,anchor.z());
        descriptor.requireAllowedWindow(descriptor.center());
        if(!descriptor.containsBody(descriptor.walkEntryBody()))throw new IllegalStateException("Earth entry exceeds finite body envelope");
        cancellationCheck.run();
        return new RollingEarthPreparation.Prepared(provider,descriptor,sampler,earth.sourceUrl(),earth.verticalDatum(),
                Thread.currentThread().getName(),Math.max(0,System.nanoTime()-start),evidence);
    }
    static float entryYaw(){return (float)EarthAlpineDefinition.ENTRY_YAW;}
    static float entryPitch(){return (float)EarthAlpineDefinition.ENTRY_PITCH;}
}

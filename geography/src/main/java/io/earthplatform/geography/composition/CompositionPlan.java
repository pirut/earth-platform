package io.earthplatform.geography.composition;

import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Versioned hard-edge source selection. No blending, datum conversion, content-dependent fallbacks or implicit updates. */
public final class CompositionPlan {
    public static final String POLICY = "source-select-hard-edge-closed-borders-no-fallback-v1";
    public static final int MAX_SOURCES=32,MAX_TOTAL_RECTANGLES=1024;
    private final List<SourceBinding> sources;
    private final VerticalDatum outputDatum;
    private final String fingerprint;
    public CompositionPlan(VerticalDatum outputDatum,List<SourceBinding> sources) {
        this.outputDatum=Objects.requireNonNull(outputDatum,"Unknown output datum");Objects.requireNonNull(sources);
        if(sources.isEmpty()||sources.size()>MAX_SOURCES)throw new IllegalArgumentException("Composition needs1..32 sources");
        var ids=new HashSet<String>();int rectangles=0;
        for(SourceBinding s:sources) {
            if(!ids.add(s.id()))throw new IllegalArgumentException("Duplicate source ID: "+s.id());
            if(s.datumEvidence().datum()!=outputDatum)throw new IllegalArgumentException("Incompatible datum for "+s.id()+": "+s.datumEvidence().datum().authorityCode());
            rectangles+=s.coverage().rectangles().size();
        }
        if(rectangles>MAX_TOTAL_RECTANGLES)throw new IllegalArgumentException("Composition coverage exceeds1024 rectangles");
        this.sources=sources.stream().sorted(Comparator.comparingDouble((SourceBinding s)->s.provenance().resolutionArcSeconds())
            .thenComparing(Comparator.comparingInt(SourceBinding::priority).reversed()).thenComparing(SourceBinding::id)).toList();
        this.fingerprint=hashPlan();
    }
    public List<SourceBinding> sources(){return sources;}
    public VerticalDatum outputDatum(){return outputDatum;}
    public String fingerprint(){return fingerprint;}
    public SourceBinding select(GeoPoint point) {
        for(SourceBinding source:sources)if(source.coverage().covers(point))return source;
        throw new MissingTerrainException("No composed source covers "+point.latitude()+","+point.longitude());
    }
    /** Binds a new save identity without modifying an existing identity or its seed, generator version or actual block palette. */
    public WorldIdentity deriveWorldIdentity(WorldIdentity base) {
        byte[] identity=encode(out->{write(out,"composed-save-identity-v1");write(out,base.datasetVersion());write(out,fingerprint);});
        return new WorldIdentity(base.seed(),base.generatorId()+"/source-select-v1",base.generatorVersion(),WorldManifest.sha256(identity),base.blockPaletteVersion());
    }
    private String hashPlan() {
        return WorldManifest.sha256(encode(out->{
            write(out,POLICY);write(out,outputDatum.authorityCode());out.writeInt(sources.size());
            for(SourceBinding s:sources) {
                write(out,s.id());write(out,s.sourceFingerprint());out.writeInt(s.priority());
                var p=s.provenance();write(out,p.id());write(out,p.release());write(out,p.verticalDatum());out.writeDouble(p.resolutionArcSeconds());
                write(out,p.license());write(out,p.source().toASCIIString());write(out,p.quality());write(out,p.attribution());
                write(out,s.datumEvidence().datum().authorityCode());write(out,s.datumEvidence().source().toASCIIString());write(out,s.datumEvidence().sha256());
                out.writeInt(s.coverage().rectangles().size());
                for(var r:s.coverage().rectangles()){out.writeDouble(r.west());out.writeDouble(r.south());out.writeDouble(r.north());out.writeDouble(r.eastwardSpan());out.writeDouble(r.east());}
            }
        }));
    }
    @FunctionalInterface private interface Encoder {void encode(DataOutputStream out)throws IOException;}
    private static byte[] encode(Encoder encoder) {
        try {var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){encoder.encode(out);}return bytes.toByteArray();}
        catch(IOException impossible){throw new UncheckedIOException(impossible);}
    }
    private static void write(DataOutputStream out,String text)throws IOException {byte[] bytes=text.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
}

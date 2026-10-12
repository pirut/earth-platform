package io.earthplatform.minecraft.lod;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.enginebridge.EarthReplacementAdmission;
import io.earthplatform.minecraft.enginebridge.EarthReplacementCapture;
import java.nio.file.Path;
final class DhEarthReplacementTestFixtures {
    static final EarthAlpineDefinition SOURCE=source();
    static EarthAlpineDefinition source(){try{return EarthAlpineDefinition.load(Path.of(System.getProperty("earth.alpine.geography",Path.of(System.getProperty("earthplatform.test.projectDirectory","minecraft")).resolve("../geography").normalize().toString())));}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    static EarthReplacementCapture baseline(){
        var pin=DhEarthReplacementAdmission.PIN;var columns=new EarthAlpineDefinition.Column[20][20];
        for(int z=0;z<20;z++)for(int x=0;x<20;x++)columns[z][x]=SOURCE.column(pin.minBlockX()-2+x,pin.minBlockZ()-2+z);
        try(var builder=new EarthReplacementCapture.Builder(pin)){
            for(int y=0;y<20;y++)for(int z=0;z<20;z++)for(int x=0;x<20;x++)builder.setNext(material(columns[z][x].materialAt(pin.minBlockY()-2+y)));
            return builder.finish();
        }
    }
    static int material(EarthAlpineDefinition.Material material){return switch(material){case AIR->0;case STONE->1;case ANDESITE->3;case TUFF->4;case SNOW_BLOCK->5;};}
    static DhReplacementInput.Data input(EarthReplacementCapture capture,long revision){
        var world=new WorldIdentity(30,"earth-alpine-proof","1",SOURCE.identity(),"six-material");
        var certificate=new DhReplacementInput.Certificate(world,1,1,1,capture.position(),revision,"a".repeat(64),"b".repeat(64),EarthReplacementAdmission.MATERIAL_POLICY);
        return DhReplacementInput.content(certificate,DhVoxelReplacementGeometry.captureEarth(capture));
    }
    public static void main(String[] args){var capture=baseline();System.out.println("OUTSIDE_PIN "+DhEarthReplacementAdmission.outsidePinIdentity(capture)+" capture="+capture.contentIdentity()+" faces="+EarthReplacementAdmission.faces(capture));}
}

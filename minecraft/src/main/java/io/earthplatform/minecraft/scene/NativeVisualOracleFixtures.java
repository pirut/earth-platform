package io.earthplatform.minecraft.scene;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Bounded whole-page inputs for the installed oracle. Fixture generation does not establish renderer parity. */
public final class NativeVisualOracleFixtures {
    private NativeVisualOracleFixtures() {}
    public static final List<String> NAMES = List.of("empty", "full-stone", "flat-snow-stone", "exposed-cliff",
            "checkerboard-one-section", "mixed-materials", "edit-hole-and-placement", "section-and-page-borders",
            "negative-coordinates", "earth-high-coordinates", "y-separated-4096", "page-local-corners", "unknown-halo-rejection");
    public static NativeVisualCpuBuilder.Input input(int fixture) {
        if(fixture<0||fixture>=NAMES.size())throw new IllegalArgumentException("Unknown native oracle fixture");
        byte[] states=new byte[NativeVisualCpuBuilder.HALO_BYTES];
        int originX=0,originY=0,originZ=0;
        if(fixture==8){originX=-128;originY=-4096;originZ=-256;}
        if(fixture==9){originX=Math.floorDiv(9700000,64)*64;originY=8192;originZ=Math.floorDiv(-3100000,64)*64;}
        if(fixture==10){originX=-128;originY=0;originZ=-256;}
        for(int y=-1;y<=64;y++)for(int z=-1;z<=64;z++)for(int x=-1;x<=64;x++) {
            boolean inside=x>=0&&y>=0&&z>=0&&x<64&&y<64&&z<64;
            int code=switch(fixture){
                case 0->0;
                case 1->inside?1:0;
                case 2->y<15?1:y==15?5:0;
                case 3->y<((x<31)?7:38)?1:0;
                case 4->inside&&x<16&&y<16&&z<16&&((x+y+z)&1)==0?1:0;
                case 5->inside&&((x*7+y*11+z*13)&15)<7?1+Math.floorMod(x+y+z,5):0;
                case 6->y<20&&!(x>=6&&x<=10&&y>=5&&y<=12&&z>=7&&z<=11)?1:x==8&&y==25&&z==9?2:0;
                case 7->inside&&((x%16==0)||(y%16==15)||(z%16==0))?1+Math.floorMod(x+y+z,5):0;
                case 8,9,10->y<7+Math.floorMod(x*13+z*7,29)?1+Math.floorMod(x+y+z,5):0;
                case 11->inside&&(x==0||x==63)&&(y==0||y==63)&&(z==0||z==63)?1:0;
                case 12->x==0&&y==0&&z==0?1:x==-1&&y==0&&z==0?255:0;
                default->throw new AssertionError();
            };
            states[NativeVisualCpuBuilder.Input.haloIndex(x,y,z)]=(byte)code;
        }
        int[] members;
        if(fixture==4||fixture==12)members=new int[]{0};
        else {members=new int[64];for(int i=0;i<64;i++)members[i]=i;}
        byte[] label=("actual-native-page-oracle-v1:"+NAMES.get(fixture)+":"+originX+":"+originY+":"+originZ).getBytes(StandardCharsets.UTF_8);
        return new NativeVisualCpuBuilder.Input(originX,originY,originZ,states,members,NativeVisualCapsule.sha256(label,label.length));
    }
}

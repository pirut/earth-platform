package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeTickets;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.AnalyticNativeCubeProvider;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Versioned diagnostic provider capability; no general CubeProvider is assumed to be a heightfield. */
public enum CoreGateProfile {
    BASE("base",128), ABYSS("abyss",-11000), SUMMIT("summit",8849);
    public static final int WIRE_VERSION=1;
    public static final String VERSION="native-player-profile-v1";
    public static final int GAMEPLAY_MIN_Y=-11008, GAMEPLAY_MAX_Y_EXCLUSIVE=8864;
    private final String id;
    private final int anchorY;
    CoreGateProfile(String id,int anchorY){this.id=id;this.anchorY=anchorY;}
    public String id(){return id;}
    public int anchorY(){return anchorY;}
    public CubePos center(){return CubePos.fromBlock(0,anchorY,0);}
    public CubePos target(){return center();}
    public BlockPos commandEdit(){return new BlockPos(4,anchorY+1,4);}
    public Vec3 entry(){return new Vec3(4.5,anchorY+3.0,-1.5);}
    public AABB walkEntryBody(){var p=entry();return new AABB(p.x-.3,p.y,p.z-.3,p.x+.3,p.y+1.8,p.z+.3);}
    public io.earthplatform.minecraft.scene.SceneRenderEnvelope renderEnvelope(){return this==BASE?io.earthplatform.minecraft.scene.SceneRenderEnvelope.NATIVE:io.earthplatform.minecraft.scene.SceneRenderEnvelope.PLAYER_DIAGNOSTIC_PADDING;}
    public int renderMinY(){return this==BASE?GAMEPLAY_MIN_Y:-11040;}
    public int renderMaxYExclusive(){return this==BASE?GAMEPLAY_MAX_Y_EXCLUSIVE:8896;}
    public List<CubePos> owners(){return CubeTickets.box(center(),2,2,125).stream().sorted().toList();}
    public List<CubePos> meshTargets(){return CubeTickets.box(center(),1,1,27).stream().sorted().toList();}
    public boolean distantMaskEligible(CubePos p){return p.minBlockY()>=GAMEPLAY_MIN_Y&&p.minBlockY()+16<=GAMEPLAY_MAX_Y_EXCLUSIVE;}
    /** Callers must separately prove each member is an actual current published native mesh. */
    public List<CubePos> distantMaskTargets(){return meshTargets().stream().filter(this::distantMaskEligible).toList();}
    public boolean gameplayContains(long y){return y>=GAMEPLAY_MIN_Y&&y<GAMEPLAY_MAX_Y_EXCLUSIVE;}
    public String saveDirectory(){return "earthplatform-full-height-player-gate-v1/"+id;}
    public AnalyticNativeCubeProvider provider(EnginePaletteContext palette){return new AnalyticNativeCubeProvider(0,AnalyticNativeCubeProvider.Config.boundedCollision(anchorY),palette);}
    public WorldIdentity world(EnginePaletteContext palette){return provider(palette).identity();}
    public String fingerprint(EnginePaletteContext palette){
        var w=world(palette);
        String canonical=VERSION+"\n"+id+"\nanchor="+anchorY+"\ncenter="+center()+"\ngameplay="+GAMEPLAY_MIN_Y+":"+GAMEPLAY_MAX_Y_EXCLUSIVE
                +"\nrender="+renderMinY()+":"+renderMaxYExclusive()+"\nflat-far-v1="+anchorY+"\nworld="+w+"\n";
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    public void write(FriendlyByteBuf b,EnginePaletteContext palette){b.writeVarInt(WIRE_VERSION);b.writeUtf(id,16);b.writeUtf(fingerprint(palette),64);}
    /** Reads only its own bounded descriptor. The enclosing packet must reject trailing fields. */
    public static CoreGateProfile read(FriendlyByteBuf b,EnginePaletteContext palette){
        if(b.readVarInt()!=WIRE_VERSION)throw new IllegalArgumentException("Unsupported native player profile version");
        var profile=byId(b.readUtf(16));if(!profile.fingerprint(palette).equals(b.readUtf(64)))throw new IllegalArgumentException("Native player profile fingerprint mismatch");return profile;
    }
    public static CoreGateProfile byId(String id){for(var p:values())if(p.id.equals(id))return p;throw new IllegalArgumentException("Unsupported native player profile");}
    public static CoreGateProfile atAnchor(int y){for(var p:values())if(p.anchorY==y)return p;throw new IllegalArgumentException("Unsupported native player anchor");}
}

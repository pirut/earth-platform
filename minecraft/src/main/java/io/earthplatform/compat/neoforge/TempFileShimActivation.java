package io.earthplatform.compat.neoforge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Explicit, exact installed-combination admission. This never edits another mod. */
public final class TempFileShimActivation {
    public static final String PROPERTY="earthplatform.experimental.installedDhTempFileShim";
    public static final String NEOFORGE_VERSION="21.1.256", DH_VERSION="3.3.3";
    public static final String DH_SHA256="864e70def5b0d54d619b940b66bb2784e91d3f3fc253064fdc1b3fc5786cbfab";
    private TempFileShimActivation(){}
    public static boolean select(boolean requested,String neoForge,String dh) {
        if(!requested)return false;
        if(!NEOFORGE_VERSION.equals(neoForge)||!DH_VERSION.equals(dh))
            throw new IllegalStateException("Temporary-file shim requires explicitly selected NeoForge21.1.256 and installed DH3.3.3; observed "+neoForge+" / "+dh);
        return true;
    }
    public static void verifyInstalledDh(Path artifact) {
        try {
            if(artifact==null||!Files.isRegularFile(artifact)||Files.size(artifact)>128L*1024*1024)
                throw new IllegalStateException("Temporary-file shim requires the exact installed DH JAR");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream input=Files.newInputStream(artifact)) {
                byte[] bytes=new byte[65536];long total=0;int n;
                while((n=input.read(bytes))!=-1){total+=n;if(total>128L*1024*1024)throw new IllegalStateException("Installed DH artifact exceeded verification bound");digest.update(bytes,0,n);}
            }
            if(!DH_SHA256.equals(HexFormat.of().formatHex(digest.digest())))throw new IllegalStateException("Temporary-file shim refuses changed installed DH artifact");
        }catch(IOException e){throw new IllegalStateException("Cannot verify installed DH temporary-file compatibility input",e);}
        catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
}

package io.earthplatform.compat.dh;

import cpw.mods.niofs.union.UnionFileSystemProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;
import java.util.jar.Manifest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class InstalledPlayableInventoryTest {
 @TempDir Path temp;
 static InstalledIrisRuntime.InstalledMod mod(String id,String version,Path path){return new InstalledIrisRuntime.InstalledMod(id,version,path,InstalledPlayableInventoryTest.class.getClassLoader(),id,URI.create("file:/test-authoritative-module"));}
 static List<InstalledIrisRuntime.InstalledMod> metadata(){return InstalledPlayableInventory.VERSIONS.entrySet().stream().map(e->mod(e.getKey(),e.getValue(),Path.of("/test/"+e.getKey()))).toList();}
 static Path artifact(String property){String value=System.getProperty(property);assertNotNull(value,"Explicit pinned artifact required: "+property);return Path.of(value);}
 static Path sodium(){return artifact("earthplatform.installedSodiumTestJar");}
 static FileSystem mount(Path entry)throws Exception{return FileSystems.newFileSystem(URI.create("jij:"+entry.toAbsolutePath().toUri().getRawSchemeSpecificPart()),Map.of("packagePath",entry));}
 @Test void exactTenMetadataEntriesAreAdmittedWithoutOrderingAssumption()throws Exception{var list=new ArrayList<>(metadata());Collections.reverse(list);var calls=new AtomicInteger();InstalledPlayableInventory.verify(list,actual->{calls.incrementAndGet();assertEquals(InstalledPlayableInventory.VERSIONS.keySet(),actual.keySet());assertThrows(UnsupportedOperationException.class,()->actual.clear());});assertEquals(1,calls.get());}
 @Test void everyMissingVersionExtraAndDuplicateRejectsBeforeOrigins(){var good=metadata();var calls=new AtomicInteger();InstalledPlayableInventory.OriginCheck check=m->calls.incrementAndGet();for(int i=0;i<good.size();i++){var missing=new ArrayList<>(good);missing.remove(i);assertThrows(IOException.class,()->InstalledPlayableInventory.verify(missing,check));var wrong=new ArrayList<>(good);var m=wrong.get(i);wrong.set(i,mod(m.id(),m.version()+"-changed",m.path()));assertThrows(IOException.class,()->InstalledPlayableInventory.verify(wrong,check));var foreign=new ArrayList<>(good);foreign.set(i,mod("unapproved_mod","1",m.path()));assertThrows(IOException.class,()->InstalledPlayableInventory.verify(foreign,check));var duplicate=new ArrayList<>(good);duplicate.set(i,good.get((i+1)%good.size()));assertThrows(IOException.class,()->InstalledPlayableInventory.verify(duplicate,check));}var extra=new ArrayList<>(good);extra.add(mod("extra","1",Path.of("/extra")));assertThrows(IOException.class,()->InstalledPlayableInventory.verify(extra,check));assertEquals(0,calls.get());}
 @Test void originFailureIsPreservedAndNextAdmissionDoesNotReuseSuccess()throws Exception{var failure=new IOException("origin failed");InstalledPlayableInventory.verify(metadata(),m->{});assertSame(failure,assertThrows(IOException.class,()->InstalledPlayableInventory.verify(metadata(),m->{throw failure;})));}
 @Test void exactOfficialMetadataAndEveryNestedOriginVerifyWithoutTakingOwnership()throws Exception{
  try(var outer=new UnionFileSystemProvider().newFileSystem(null,sodium())){
   var nestedOwners=new ArrayList<FileSystem>();try{var mods=new LinkedHashMap<String,InstalledIrisRuntime.InstalledMod>();for(var m:metadata())mods.put(m.id(),m);
    mods.put("distanthorizons",mod("distanthorizons",InstalledDhResolver.VERSION,artifact("earthplatform.installedDhTestJar")));mods.put("iris",mod("iris",InstalledIrisRuntime.IRIS_VERSION,artifact("earthplatform.installedIrisTestJar")));
    for(var member:InstalledSodiumOrigin.MEMBERS.values()){var nested=mount(outer.getPath("/"+member.entry()));nestedOwners.add(nested);mods.put(member.id(),mod(member.id(),member.version(),nested.getPath("/")));var physical=InstalledSodiumOrigin.verifiedOuter(mods.get(member.id()),member);assertTrue(Files.isSameFile(sodium(),physical));
     try(var wrapper=new UnionFileSystemProvider().newFileSystem(null,nested.getPath("/"))){assertThrows(IOException.class,()->InstalledSodiumOrigin.verifySameOuter(mod(member.id(),member.version(),wrapper.getPath("/")),physical));if(member==InstalledSodiumOrigin.SODIUM)InstalledSodiumOrigin.verify(mod(member.id(),member.version(),wrapper.getPath("/")));assertTrue(wrapper.isOpen());assertTrue(nested.isOpen());}
     try(var input=Files.newInputStream(nested.getPath("/META-INF/MANIFEST.MF"))){if(member==InstalledSodiumOrigin.SODIUM)assertNull(new Manifest(input).getMainAttributes().getValue("Implementation-Version"));else assertEquals(member.version(),new Manifest(input).getMainAttributes().getValue("Implementation-Version"));}
    }
    InstalledPlayableInventory.verify(new ArrayList<>(mods.values()),InstalledPlayableInventory::verifyOrigins);assertTrue(outer.isOpen());assertTrue(nestedOwners.stream().allMatch(FileSystem::isOpen));
   }finally{for(int i=nestedOwners.size()-1;i>=0;i--)nestedOwners.get(i).close();}
  }
 }
 @Test void identicalSecondOuterCannotMasqueradeAsSamePhysicalLoadedSodium()throws Exception{var copy=Files.copy(sodium(),temp.resolve("same-official-bytes.jar"));var dependency=InstalledSodiumOrigin.MEMBERS.get("fabric_api_base");try(var outer=new UnionFileSystemProvider().newFileSystem(null,copy);var nested=mount(outer.getPath("/"+dependency.entry()))){var m=mod(dependency.id(),dependency.version(),nested.getPath("/"));assertTrue(Files.isSameFile(copy,InstalledSodiumOrigin.verifiedOuter(m,dependency)));assertThrows(IOException.class,()->InstalledSodiumOrigin.verifySameOuter(m,sodium()));assertTrue(outer.isOpen());assertTrue(nested.isOpen());}}
 @Test void wrongMemberNonRootPhysicalExtractionUnknownPinAndVersionFailClosed()throws Exception{try(var outer=new UnionFileSystemProvider().newFileSystem(null,sodium())){for(var member:InstalledSodiumOrigin.MEMBERS.values()){try(var nested=mount(outer.getPath("/"+member.entry()))){var valid=mod(member.id(),member.version(),nested.getPath("/"));assertThrows(IOException.class,()->InstalledSodiumOrigin.verifiedOuter(mod(member.id(),member.version(),nested.getPath("/META-INF")),member));assertThrows(IOException.class,()->InstalledSodiumOrigin.verifiedOuter(mod(member.id(),member.version()+"x",nested.getPath("/")),member));assertThrows(IOException.class,()->InstalledSodiumOrigin.verifiedOuter(valid,new InstalledSodiumOrigin.Member(member.id(),member.version(),member.entry(),member.bytes(),member.sha256())));for(var other:InstalledSodiumOrigin.MEMBERS.values())if(other!=member)assertThrows(IOException.class,()->InstalledSodiumOrigin.verifiedOuter(mod(other.id(),other.version(),nested.getPath("/")),other));Path extracted=temp.resolve(member.id()+".jar");Files.copy(outer.getPath("/"+member.entry()),extracted);assertThrows(IOException.class,()->InstalledSodiumOrigin.verifiedOuter(mod(member.id(),member.version(),extracted),member));assertTrue(nested.isOpen());}}}}
 @Test void changedOuterBytesRejectEvenWhenMountedInnerBytesRemainExact()throws Exception{var altered=Files.copy(sodium(),temp.resolve("modified-service.jar"));byte[] bytes=Files.readAllBytes(altered);bytes[10]^=1;Files.write(altered,bytes);try(var outer=new UnionFileSystemProvider().newFileSystem(null,altered);var nested=mount(outer.getPath("/"+InstalledSodiumOrigin.ENTRY))){assertThrows(IOException.class,()->InstalledSodiumOrigin.verifiedOuter(mod("sodium",InstalledIrisRuntime.SODIUM_VERSION,nested.getPath("/")),InstalledSodiumOrigin.SODIUM));assertTrue(nested.isOpen());}}
}

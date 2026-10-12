package io.earthplatform.minecraft.survival.host;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurvivalDataPackIdentityTest {
    private static final SurvivalDataPackIdentity.Limits LIMITS = new SurvivalDataPackIdentity.Limits(8, 32, 64, 1000, 4000);
    private static PackResources pack(String id) {
        return (PackResources)Proxy.newProxyInstance(PackResources.class.getClassLoader(), new Class[]{PackResources.class},
                (proxy, method, args) -> { if(method.getName().equals("packId")) return id; throw new UnsupportedOperationException(method.getName()); });
    }
    private static Resource resource(String pack, String value) { return new Resource(pack(pack), () -> new ByteArrayInputStream(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
    private static ResourceManager resources(List<String> packs, Map<ResourceLocation,List<Resource>> files) {
        return (ResourceManager)Proxy.newProxyInstance(ResourceManager.class.getClassLoader(), new Class[]{ResourceManager.class},
                (proxy, method, args) -> switch(method.getName()) {
                    case "listPacks" -> packs.stream().map(SurvivalDataPackIdentityTest::pack);
                    case "listResourceStacks" -> {
                        String prefix = (String)args[0];
                        if (prefix.isEmpty()) throw new AssertionError("Actual native packs reject the empty resource path");
                        var result = new LinkedHashMap<ResourceLocation,List<Resource>>();
                        files.forEach((key,value) -> { if (key.getPath().startsWith(prefix + "/")) result.put(key,value); });
                        yield result;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
    private static ResourceLocation key(String path) { return ResourceLocation.fromNamespaceAndPath("minecraft", path); }
    private static String capture(ResourceManager manager) throws IOException { return SurvivalDataPackIdentity.capture(manager,"registry","profile",LIMITS).digest(); }
    @Test void canonicalPathOrderingAndExactLayerOrderAreBothBound() throws Exception {
        Resource a=resource("vanilla","one"),b=resource("mod_resources","two");
        var forward=new LinkedHashMap<ResourceLocation,List<Resource>>();forward.put(key("tags/item/a.json"),List.of(a,b));forward.put(key("recipe/z.json"),List.of(a));
        var reverse=new LinkedHashMap<ResourceLocation,List<Resource>>();reverse.put(key("recipe/z.json"),List.of(a));reverse.put(key("tags/item/a.json"),List.of(a,b));
        String baseline=capture(resources(List.of("vanilla","mod_resources"),forward));
        assertEquals(baseline,capture(resources(List.of("vanilla","mod_resources"),reverse)));
        reverse.put(key("tags/item/a.json"),List.of(b,a));assertNotEquals(baseline,capture(resources(List.of("vanilla","mod_resources"),reverse)));
    }
    @Test void lowerTagLayerPackOrderRegistryAndExecutableProfileChangesInvalidateIdentity() throws Exception {
        var files=Map.of(key("tags/item/logs.json"),List.of(resource("vanilla","base"),resource("mod_resources","override")));
        var manager=resources(List.of("vanilla","mod_resources"),files);String baseline=capture(manager);
        assertNotEquals(baseline,capture(resources(List.of("mod_resources","vanilla"),files)));
        assertNotEquals(baseline,SurvivalDataPackIdentity.capture(manager,"registry2","profile",LIMITS).digest());
        assertNotEquals(baseline,SurvivalDataPackIdentity.capture(manager,"registry","profile2",LIMITS).digest());
        assertNotEquals(baseline,capture(resources(List.of("vanilla","mod_resources"),Map.of(key("tags/item/logs.json"),List.of(resource("vanilla","changed"),resource("mod_resources","override"))))));
    }
    @Test void byteLimitConsumesOnlyOneExtraByteAndClosesTheStream() {
        AtomicInteger reads=new AtomicInteger(),closed=new AtomicInteger();
        Resource infinite=new Resource(pack("vanilla"),()->new InputStream(){@Override public int read(){reads.incrementAndGet();return 7;}@Override public void close(){closed.incrementAndGet();}});
        var manager=resources(List.of("vanilla"),Map.of(key("loot_table/test.json"),List.of(infinite)));
        assertThrows(IOException.class,()->SurvivalDataPackIdentity.capture(manager,"r","p",new SurvivalDataPackIdentity.Limits(1,1,1,10,100)));
        assertEquals(11,reads.get());assertEquals(1,closed.get());
    }
    @Test void countAndTotalBoundsFailWithoutTruncatingTheDigest() {
        var manager=resources(List.of("a","b"),Map.of(key("recipe/a.json"),List.of(resource("a","four"),resource("b","four"))));
        assertThrows(IOException.class,()->SurvivalDataPackIdentity.capture(manager,"r","p",new SurvivalDataPackIdentity.Limits(1,1,2,10,100)));
        assertThrows(IOException.class,()->SurvivalDataPackIdentity.capture(manager,"r","p",new SurvivalDataPackIdentity.Limits(2,1,1,10,100)));
        assertThrows(IOException.class,()->SurvivalDataPackIdentity.capture(manager,"r","p",new SurvivalDataPackIdentity.Limits(2,1,2,10,7)));
    }
    @Test void maximumLongByteCeilingsDoNotOverflowAndObservedBytesRemainExact() throws Exception {
        var manager=resources(List.of("a"),Map.of(key("recipe/a.json"),List.of(resource("a","abc"))));
        var result=SurvivalDataPackIdentity.capture(manager,"r","p",new SurvivalDataPackIdentity.Limits(1,1,1,Long.MAX_VALUE,Long.MAX_VALUE));
        assertEquals(3,result.bytes());assertEquals(1,result.paths());assertEquals(1,result.layers());assertEquals(1,result.packs());
    }
    @Test void everyAdmittedDomainUsesNonemptyNativePrefixAndUnionLimit() throws Exception {
        var files = Map.of(key("recipe/oak_planks.json"),List.of(resource("a","recipe")),
                key("loot_table/blocks/oak_log.json"),List.of(resource("a","loot")),
                key("advancement/story/root.json"),List.of(resource("a","advancement")),
                key("tags/item/logs.json"),List.of(resource("a","base"),resource("b","override")));
        var manager = resources(List.of("a","b"),files);
        var capture = SurvivalDataPackIdentity.capture(manager,"r","p",LIMITS);
        assertEquals(4,capture.paths()); assertEquals(5,capture.layers());
        for (String root : List.of("recipe","loot_table","advancement","tags")) assertEquals(1,capture.domainPaths().get(root));
        assertThrows(IOException.class, () -> SurvivalDataPackIdentity.capture(manager,"r","p",new SurvivalDataPackIdentity.Limits(2,3,10,100,1000)));
        assertThrows(UnsupportedOperationException.class, () -> capture.domainPaths().put("unowned",1));
    }
    @Test void knownRecipeLootAndAdvancementBytesAllBindTheIdentity() throws Exception {
        for (String root : List.of("recipe","loot_table","advancement","loot_modifiers")) {
            var location=key(root+"/sentinel.json");
            assertNotEquals(capture(resources(List.of("a"),Map.of(location,List.of(resource("a","before"))))),
                    capture(resources(List.of("a"),Map.of(location,List.of(resource("a","after"))))));
        }
    }

}

package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** One cumulative admission ledger for retained snapshots, prediction bytes and delivery buffers. */
final class NativeMenuBudget {
    private final int limit;
    private int used;
    private boolean scratchReserved;
    NativeMenuBudget(int limit) {
        if (limit < 1 || limit > 16 * 1024 * 1024) throw new IllegalArgumentException("Bounded native menu byte allowance required");
        this.limit = limit;
    }
    void reserve(int bytes) {
        if (bytes < 0 || bytes > limit - used) throw new SurvivalRefusal("Native menu cumulative byte allowance exhausted");
        used += bytes;
    }
    NativeMenuBudget scratch() {
        if (!scratchReserved) { reserve(1024 * 1024); scratchReserved = true; }
        // Reusable bounded scratch. No object captured here may become retained authority.
        return new NativeMenuBudget(1024 * 1024);
    }
    int used() { return used; }
    int remaining() { return limit - used; }
    NativeStackSnapshot image(ItemStack stack, ServerPlayer player) { return image(stack,player,false); }
    /** Read-only result notice image. This never grants owned-item, cursor, recipe-take or drop admission. */
    NativeStackSnapshot previewImage(ItemStack stack, ServerPlayer player) { return image(stack,player,true); }
    private NativeStackSnapshot image(ItemStack stack, ServerPlayer player, boolean preview) {
        // Reserve the maximum BEFORE capture invokes component codecs or allocates its JSON tree.
        // This profile separately admits only the pinned native item/component closure.
        requireComponents(stack,preview);
        reserve(64 + NativeStackSnapshot.MAX_BYTES);
        var image = NativeStackSnapshot.capture(stack, player.registryAccess());
        // Replace the worst-case reservation only after successful bounded capture. The retained
        // image stays charged; the released difference was unused per-stack scratch headroom.
        used -= NativeStackSnapshot.MAX_BYTES - image.encodedBytes();
        return image;
    }
    static void requireComponents(ItemStack stack) { requireComponents(stack,false); }
    static void requirePreviewComponents(ItemStack stack) { requireComponents(stack,true); }
    private static void requireComponents(ItemStack stack, boolean preview) {
        if (stack == null || stack.getClass() != ItemStack.class) throw new SurvivalRefusal("Exact native stack required before codec admission");
        if (stack.isEmpty()) return; // NativeStackSnapshot's constant empty path does not traverse components.
        var item = stack.getItem();
        try {
            if(preview && item==net.minecraft.world.item.Items.WOODEN_HOE && item.getClass()==net.minecraft.world.item.HoeItem.class) {
                // Native removal of the first pickaxe ingredient temporarily derives this exact preview.
                // Capture uses the pinned ItemStack codec/copy and immutable Item prototype, never HoeItem gameplay methods.
                PinnedNativeEventAdmission.requireClassBytes(net.minecraft.world.item.HoeItem.class,"225d16fefda7a3701f5e4271e4e1ea0e4429f98d5710933b1bbf57182759b1cd");
                PinnedNativeEventAdmission.requireClassBytes(net.minecraft.world.item.Item.class,"b3741b3f525b289465e9e5757df8e8c59c596e593e7ff4f7791195a05956a636");
                PinnedNativeEventAdmission.requireClassBytes(ItemStack.class,"5808e89755643aa4c614f0835a3eabff384f94343d9d30cd54541051f5701e12");
            } else PinnedNativeEventAdmission.requireOrdinaryItemType(item);
        }
        catch (SurvivalRefusal original) {
            var diagnostic = new SurvivalRefusal("Native menu codec item refused: "
                    + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item) + " class=" + item.getClass().getName());
            diagnostic.initCause(original); throw diagnostic;
        }
        if (stack.getCount() < 1 || stack.getCount() > 64
                || net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString().length() > 64)
            throw new SurvivalRefusal("Native menu stack scalar encoding exceeds admitted shape");
        var components = NativeSessionMenuBoundary.read(ItemStack.class, stack, "components", net.minecraft.core.component.PatchedDataComponentMap.class);
        if (components == null || components.getClass() != net.minecraft.core.component.PatchedDataComponentMap.class)
            throw new SurvivalRefusal("Foreign native stack component map");
        var prototype = NativeSessionMenuBoundary.read(net.minecraft.core.component.PatchedDataComponentMap.class, components, "prototype", net.minecraft.core.component.DataComponentMap.class);
        var expected = NativeSessionMenuBoundary.read(net.minecraft.world.item.Item.class, item, "components", net.minecraft.core.component.DataComponentMap.class);
        if (prototype == null || prototype != expected || prototype.getClass() != nativeClass("net.minecraft.core.component.DataComponentMap$Builder$SimpleMap"))
            throw new SurvivalRefusal("Native item component prototype identity/class changed");
        var base = NativeSessionMenuBoundary.read(prototype.getClass(), prototype, "map", it.unimi.dsi.fastutil.objects.Reference2ObjectMap.class);
        if (base == null || base.getClass() != it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap.class
                && base.getClass() != it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap.class)
            throw new SurvivalRefusal("Foreign native component prototype backing map");
        if (base.size() > 16) throw new SurvivalRefusal("Native menu component prototype exceeds the pinned16-entry lookup bound");
        var patch = NativeSessionMenuBoundary.read(net.minecraft.core.component.PatchedDataComponentMap.class, components, "patch", it.unimi.dsi.fastutil.objects.Reference2ObjectMap.class);
        if (patch == null || patch.getClass() != it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap.class
                && patch.getClass() != it.unimi.dsi.fastutil.objects.Reference2ObjectMaps.emptyMap().getClass())
            throw new SurvivalRefusal("Foreign native component patch collection before traversal");
        if (patch.size() > 1) throw new SurvivalRefusal("First menu component patch exceeds admitted damage-only profile");
        for (Object object : patch.reference2ObjectEntrySet()) {
            var entry = (it.unimi.dsi.fastutil.objects.Reference2ObjectMap.Entry<?, ?>) object;
            if (entry.getKey() != net.minecraft.core.component.DataComponents.DAMAGE || !(entry.getValue() instanceof java.util.Optional<?> value)
                    || value.isEmpty() || value.get().getClass() != Integer.class || (Integer)value.get() < 0 || (Integer)value.get() > 65535)
                throw new SurvivalRefusal("Native menu component codec is outside the pinned damage-only profile");
        }
    }
    private static Class<?> nativeClass(String name) {
        try { return Class.forName(name, false, ItemStack.class.getClassLoader()); }
        catch (ClassNotFoundException | LinkageError failure) { throw new SurvivalRefusal("Pinned native component class is unavailable"); }
    }
}

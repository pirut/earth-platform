package io.earthplatform.minecraft.survival.interaction.host;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
/** Pinned original resources, not a claim that arbitrary extra transformations are admitted. */
final class NativeMiningControlPins {
    private NativeMiningControlPins() {}
    private static final java.util.Map<String,String> PINS=java.util.Map.ofEntries(
            java.util.Map.entry("net.minecraft.server.level.ServerPlayerGameMode", "b90be8fa9fedc2914a5b8f36082636f40901d7ade594b80c4d6a606838bc5c27"),
            java.util.Map.entry("net.minecraft.server.level.ServerPlayer", "d48041f234ec0a2097d1947398b14eb66ef4fb17c9574517d818147ea7df74c2"),
            java.util.Map.entry("net.minecraft.server.network.ServerGamePacketListenerImpl", "0602f43318d5bf25d5c2f0468728dd26a61cac8e899e58a86dd5a81343417138"),
            java.util.Map.entry("net.minecraft.server.network.ServerCommonPacketListenerImpl", "2626df5c94f13f877364d5c13aba9d587f2261589f039ccd89b52341eeb0a1e0"),
            java.util.Map.entry("net.minecraft.server.MinecraftServer", "f42f6ff6f3ee1b2b678c222b192fccca995686b882eb99546284e29c683d09e6"),
            java.util.Map.entry("net.minecraft.server.network.ServerConnectionListener", "0152480bcf55bf0a1abcaf861919156f0652b5ee03d5bc6a809b0a98197b3a2b"),
            java.util.Map.entry("net.minecraft.network.Connection", "43773faa0f56d9dfd7a836c32375c3ee97f42773a5925049c160291f2ad505ff"),
            java.util.Map.entry("net.minecraft.network.protocol.game.ServerboundPlayerActionPacket", "7048c18bdaedd7bf4c16d9bdcd45867bea42d1d7a77c1f2e37a824d97864584f"),
            java.util.Map.entry("net.minecraft.network.protocol.game.ServerboundPlayerActionPacket$Action", "812a9e0dcda4413ec50b35fcacfab695cd57e76fb26a92b14573afd91f7ed6f2"),
            java.util.Map.entry("net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket", "46141b4fb4553cf6b1f683440759c509c65055582e25a5edd33850de10e5980f"),
            java.util.Map.entry("net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket", "ec1ceb74fb7d0987d9b0e6338c5bed22bc3305ad30c1151ce906cb86f36b125f"),
            java.util.Map.entry("net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket", "7f992ba1c18cec2f1ab36904a38c28ece6c75b1ae8dc116c9f15d4fb6baadf8a"),
            java.util.Map.entry("net.minecraft.core.BlockPos", "15ce9da74b5be7ca930cc4d859d2015802910a169a840efedcfe7de8c69139f2"),
            java.util.Map.entry("net.minecraft.network.FriendlyByteBuf", "066baeaf87081a582a60ebbfc36521939a6c8447ef83b14c039307be76a85fd4"),
            java.util.Map.entry("net.minecraft.server.level.ServerLevel", "e4e249f6a71830ab4eda69b25ea10cfe3a3e0af5d84a6926016cab18775c4c32"),
            java.util.Map.entry("net.minecraft.gametest.framework.GameTestServer", "240a7a4968bc0134749d8251c030b16fa67ed3429f7d439d4b4b81102eff5690"),
            java.util.Map.entry("net.minecraft.world.level.Level", "e6447eec7e478face83d0299535e967e87991c549f5ffac8652fb7658b1d75b1"),
            java.util.Map.entry("net.minecraft.world.level.border.WorldBorder", "a9bbf3d5cf90b909641b0cd5878d7b2ff845b94b4e180bd62315ba3f90004f60"),
            java.util.Map.entry("net.minecraft.world.level.border.WorldBorder$BorderExtent", "1f96216e9629125567d0d0ea1778dc8acd6b1dcfcfc3355375a906b48a535df4"),
            java.util.Map.entry("net.minecraft.world.level.border.WorldBorder$StaticBorderExtent", "27315e53445b2d876f13039990d21516e1f776f7053db8bb737eb08a6ec1c43e"),
            java.util.Map.entry("net.minecraft.world.item.enchantment.EnchantmentHelper", "a4aa5ece06afcb694a177716faceaec072d5e7dbe3d05e9a8d22d9fe101a431a"),
            java.util.Map.entry("net.minecraft.world.item.enchantment.EnchantedItemInUse", "e194f59941e1c8723ed4fb55aaf80b59407bfa3bdc78ecc416c1d8738ddc1eb1"),
            java.util.Map.entry("net.minecraft.world.item.enchantment.ItemEnchantments", "69e0a34ea5a2163848698a36b3876fc18d8f070fe9965a65b3e6d4a8c3c24863"),
            java.util.Map.entry("net.minecraft.world.item.enchantment.ItemEnchantments$Mutable", "fcbeaf6e0d54d4816a5fe8e43f28f2fb80fbc2098c1a37bbdcf68912af13b787"),
            java.util.Map.entry("net.minecraft.world.item.ItemStack", "5808e89755643aa4c614f0835a3eabff384f94343d9d30cd54541051f5701e12"),
            java.util.Map.entry("net.minecraft.world.item.Item", "b3741b3f525b289465e9e5757df8e8c59c596e593e7ff4f7791195a05956a636"),
            java.util.Map.entry("net.minecraft.world.item.AirItem", "93b2794cec8378758a447fe8e24a149f138e33965b4ff479231094790d2d422b"),
            java.util.Map.entry("net.minecraft.core.component.DataComponentMap$1", "1cfac2d3ff24c4f230eacd17befa02062f20792bdbaa6a405c329a88f32442c9"),
            java.util.Map.entry("net.minecraft.core.component.DataComponentHolder", "7af02d2ad9b5dc7b3c7ea31239764e0440b90637c7b10c576271a8a0aea7e7c7"),
            java.util.Map.entry("net.minecraft.world.level.block.state.BlockBehaviour", "c8d49cd77efa5ef69a6f09b6e8a0b62bad29797cedfa40b199493cb09b2797e5"),
            java.util.Map.entry("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase", "f6913bfa8040283eb42bf139eac28650ae20ba3e248b87b1222eb0be4cc5c2e1"),
            java.util.Map.entry("net.minecraft.world.level.block.state.BlockState", "b7c92980767e378d848bbec0e7faea22d4834fca672e8b11829cb58e8676f9d3"),
            java.util.Map.entry("net.minecraft.world.level.block.Block", "083bda462c9b9d8a23ea93edca56c137d95d3069824a6a8574c0958673d76741"),
            java.util.Map.entry("net.minecraft.world.level.block.GrassBlock", "2c66352f56db3e7459b7bf59df609a851f2971898804bcd1b2741a2ba9327d8a"),
            java.util.Map.entry("net.neoforged.neoforge.common.CommonHooks", "86c8a56c77fff474bf4fe90f0354ad32698f018b5e19dc52a2725ee5ff96c73e"),
            java.util.Map.entry("net.neoforged.neoforge.common.extensions.IItemStackExtension", "244993a9cd1a5e99507c88a0a8e1d65b6ef997d44bd73ab696a510ccf2d0397e"),
            java.util.Map.entry("net.neoforged.neoforge.common.extensions.IItemExtension", "d65a413962ab6bf6cf8c33119b010dd16fcf482c114b8ae8abe5c59da951b088"),
            java.util.Map.entry("net.neoforged.neoforge.event.EventHooks", "f810377f87c95c04bb4de7a7ac68e8f4862f9ca33a34cc74e067e65e2a92d66b"),
            java.util.Map.entry("net.neoforged.neoforge.event.enchanting.GetEnchantmentLevelEvent", "2af65eb231412f8645e0ccdb51542598ead46c010da1e4e8f91321e17e4a11fc"),
            java.util.Map.entry("net.minecraft.Util", "95f40c025e74321bda3e00f1f7f454e67439d11e6a6893a688a9427a42ea2353"),
            java.util.Map.entry("net.minecraft.util.TimeSource", "832e44290572f78ca1b3b6beb388015962d840893627e9e5fa87cf3993f55205"),
            java.util.Map.entry("net.minecraft.util.TimeSource$NanoTimeSource", "ce780b16aa074fea355557437bebf3b9c3dfaa71b97030ef936fdb9cb504d5b1"));
    static void require(Class<?> type) {
        String digest=PINS.get(type.getName());
        if(digest==null)throw new SurvivalRefusal("No native mining pin for "+type.getName());
        PinnedNativeEventAdmission.requireClassBytes(type,digest);
    }
    static void requireAll() {
        for(String name:PINS.keySet())try{require(Class.forName(name,false,NativeMiningControlPins.class.getClassLoader()));}
        catch(ClassNotFoundException failure){throw new SurvivalRefusal("Pinned native mining class unavailable: "+name);}
    }
}

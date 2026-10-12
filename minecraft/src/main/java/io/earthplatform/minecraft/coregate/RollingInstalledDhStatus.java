package io.earthplatform.minecraft.coregate;

import com.google.gson.GsonBuilder;
import com.mojang.brigadier.Command;
import net.minecraft.commands.Commands;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import io.earthplatform.minecraft.EarthPlatformMod;
import java.nio.file.Files;

/** Explicit read-only installed-mod receipt, available in ordinary worlds before and after the gate. */
@EventBusSubscriber(modid=EarthPlatformMod.MOD_ID,value=Dist.CLIENT)
public final class RollingInstalledDhStatus {
    private RollingInstalledDhStatus(){}
    @SubscribeEvent public static void register(RegisterClientCommandsEvent event){
        event.getDispatcher().register(Commands.literal("earth_dh_status").executes(context->{
            var j=RollingGateClient.installedDhStatus();
            try{
                var dir=Minecraft.getInstance().gameDirectory.toPath().resolve("earthplatform-evidence/installed-dh-status");
                Files.createDirectories(dir);var file=dir.resolve(System.currentTimeMillis()+"-"+j.get("actualFrame").getAsLong()+".json");
                Files.writeString(file,new GsonBuilder().setPrettyPrinting().create().toJson(j)+"\n");
                EarthPlatformMod.LOGGER.info("INSTALLED_DH_STATUS {}",j);
                context.getSource().sendSuccess(()->Component.literal("Installed DH: "+j.get("status").getAsString()+"; retained core="+j.get("retainedSession").getAsBoolean()+". Receipt saved."),false);
                return Command.SINGLE_SUCCESS;
            }catch(java.io.IOException failure){
                EarthPlatformMod.LOGGER.error("INSTALLED_DH_STATUS_WRITE_FAILED",failure);
                context.getSource().sendFailure(Component.literal("Installed DH status receipt failed: "+failure.getMessage()));return 0;
            }
        }));
    }
}

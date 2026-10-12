package io.earthplatform.minecraft.survival.interaction.host;
import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
/** Exact MOVE_PACKET read capability. Neither generic binding getters nor ticks are licensed. */
public final class NativePlayerStepReadScope {
 private static final ThreadLocal<NativeEffectDeliveryHost.PlayerStepPlan> ACTIVE=new ThreadLocal<>();
 private NativePlayerStepReadScope(){}
 static void prepare(NativeEffectDeliveryHost.PlayerStepPlan plan){
  if(ACTIVE.get()!=null)throw refusal("Native move preparation reentered");plan.beginPreparation();ACTIVE.set(plan);
  try{plan.prepareNative();plan.requireHealthy();}catch(RuntimeException|Error failure){plan.poison(failure);throw failure;}
  finally{ACTIVE.remove();plan.endPreparation();}
 }
 static void execute(NativeEffectDeliveryHost.PlayerStepPlan plan){
  if(ACTIVE.get()!=null)throw refusal("Native move delivery reentered");ACTIVE.set(plan);
  try{plan.enter();plan.runNative();plan.requireHealthy();}catch(RuntimeException|Error failure){plan.poison(failure);throw failure;}
  finally{ACTIVE.remove();plan.close();}
 }
 public static boolean permits(SurvivalLevelBinding binding){var p=ACTIVE.get();return p!=null&&p.permits(binding);}
 public static BlockState read(SurvivalLevelBinding binding,BlockPos pos){return require(binding).read(pos);}
 public static boolean hasResidentBox(SurvivalLevelBinding binding,int x1,int y1,int z1,int x2,int y2,int z2){return require(binding).hasResidentBox(x1,y1,z1,x2,y2,z2);}
 public static boolean enterMove(ServerGamePacketListenerImpl receiver,ServerPlayer source,ServerboundMovePlayerPacket packet){var p=ACTIVE.get();return p!=null&&p.enterMove(receiver,source,packet);}
 public static boolean permitsKinematics(Entity source){var p=ACTIVE.get();return p!=null&&p.permitsKinematics(source);}
 public static boolean fluidResidency(Entity source){var p=ACTIVE.get();return p==null?source.touchingUnloadedChunk():p.residency(source,false);}
 public static boolean fallResidency(ServerPlayer source){var p=ACTIVE.get();return p==null?source.touchingUnloadedChunk():p.residency(source,true);}
 public static void beforeAward(PlayerAdvancements receiver,ServerPlayer source,AdvancementHolder holder,String criterion){var p=ACTIVE.get();if(p!=null)p.rejectEffect(source,"advancement award");}
 public static void rejectEffect(Entity source,String operation){var p=ACTIVE.get();if(p!=null)p.rejectEffect(source,operation);}
 public static void rejectOutput(Object receiver){var p=ACTIVE.get();if(p!=null)p.rejectOutput(receiver);}
 public static void terrainRefusal(SurvivalLevelBinding binding,String operation){var p=ACTIVE.get();if(p!=null){p.permits(binding);throw refusal("Unexpected native MOVE terrain query: "+operation);}}
 private static NativeEffectDeliveryHost.PlayerStepPlan require(SurvivalLevelBinding binding){var p=ACTIVE.get();if(p==null)throw refusal("Absent native MOVE scope");p.permits(binding);return p;}
 private static SurvivalRefusal refusal(String message){var failure=new SurvivalRefusal(message);var p=ACTIVE.get();if(p!=null)p.poison(failure);return failure;}
}

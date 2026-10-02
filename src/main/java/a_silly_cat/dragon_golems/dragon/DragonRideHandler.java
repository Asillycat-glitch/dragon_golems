package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.Dragon_golems;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 上龙入口：让本家的<b>骑乘手杖</b>能把玩家放到龙背上。
 *
 * <p>为什么要在事件里拦，而不是直接改手杖：{@code RiderWandItem.interactLivingEntity} 里
 * 真正"骑上去"的那一步写死了 {@code DogGolemEntity}
 * （{@code if (golem instanceof DogGolemEntity e) user.startRiding(e, false);}），
 * 龙走到那里只会 {@code return true} —— 手杖判定"成功"，人却没上去，正是"点了没反应"。
 * 那条代码在 {@code dev.xkmc.modulargolems} 里，不属于本 mod，改它就得开 mixin 基础设施；
 * 而 Forge 的 {@link PlayerInteractEvent.EntityInteract} 正好在
 * <b>实体交互之前、物品交互之前</b>派发（Forge 的 {@code interactOn} 补丁第一件事就是发这个事件），
 * 所以在这里接最省事，也完全不动别人的代码。
 *
 * <p>只在"手里拿的是骑乘手杖"时才接管，其它手杖（回收 / 命令 / 召唤 / 编队）照旧走本家那套；
 * 空手右键还是本家的"徒手收回"（如果配置开了的话）。
 */
@Mod.EventBusSubscriber(modid = Dragon_golems.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DragonRideHandler {

    private DragonRideHandler() {
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof DragonGolemEntity dragon)) {
            return;
        }
        Player player = event.getEntity();
        if (!DragonGolemEntity.canRideWith(event.getItemStack())) {
            return;
        }
        // 已经骑在龙背上时不做任何事：让这次交互原样落回本家的手杖逻辑
        // （手杖"骑着傀儡时右键 = 下龙"那条分支，或者干脆什么都不做）
        if (dragon.getControllingPassenger() != null || player.getVehicle() != null) {
            return;
        }
        if (!dragon.canWandModify(player)) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            return;
        }
        // 客户端：这条龙不是"我的"就不回成功（免得本地先摆出骑乘姿势、服务端再打回来）
        if (dragon.level().isClientSide() && !dragon.canModify(player)) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            return;
        }
        // 客户端直接回成功（原版 Animal 上人也这么分端），服务端真正 startRiding
        event.setCancellationResult(dragon.startRidingFrom(player)
                ? InteractionResult.SUCCESS : InteractionResult.FAIL);
        event.setCanceled(true);
    }
}

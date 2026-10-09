package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.Dragon_golems;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.entity.PartEntity;
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
 *
 * <p><b>没有状态门槛。</b>只要手里是骑乘手杖、这条龙上还没有别的玩家，就一定能上：
 * 龙在飞、在俯冲/冲锋、在战斗、开着什么模式，一概不拦（"龙停着才能骑"那道
 * {@code isParked()} 闸门早先已经去掉）；背上已经驮着傀儡也照上，原版会把玩家插到
 * 0 号位、傀儡整体后移一格（见 {@code DragonGolemEntity#canAddPassenger}）。
 * 点身体、头颈、尾巴、翅膀都算 —— 子碰撞箱在事件里先拆回本体，见下面那一段。
 */
@Mod.EventBusSubscriber(modid = Dragon_golems.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DragonRideHandler {

    private DragonRideHandler() {
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Entity target = event.getTarget();
        // 点到的是子碰撞箱（头/颈/尾/双翼）时，认它所属的龙本体：
        // EntityInteract 是在 Player#interactOn 的最前面发的，那时还没走到
        // DragonGolemPartEntity#interact 里"转发给本体"那一步 —— 不在这里拆这一层，
        // 表现就是"点身体能骑、点翅膀没反应"（子箱又高又大，实际上很难正好点中身体那一块）。
        if (target instanceof PartEntity<?> part && part.getParent() instanceof DragonGolemEntity parent) {
            target = parent;
        }
        if (!(target instanceof DragonGolemEntity dragon)) {
            return;
        }
        Player player = event.getEntity();
        if (!DragonGolemEntity.canRideWith(event.getItemStack())) {
            return;
        }
        // 已经骑在**傀儡**背上时不做任何事（包括正骑着这条龙）：本家手杖的约定是
        // "骑着傀儡时右键 = 下龙"（{@code RiderWandItem.interactLivingEntity} 只看玩家的坐骑、
        // 根本不看目标），让这次交互原样落回它那条分支。
        // 骑着别的坐骑（马、船、矿车…）则照常接管 —— 那是"换乘"，原版 startRiding 会自己下车。
        if (player.getVehicle() instanceof AbstractGolemEntity<?, ?>) {
            return;
        }
        // 这条龙上已经有别的玩家在开：也不接管，让手杖按它自己的规则处理，别抢。
        // 注意判的是"有没有玩家在开"，不是"驾驶位上有没有人"—— 驾驶位坐着傀儡时
        // getControllingPassenger() 是 null，那种情况正是要接管的（玩家照上，傀儡后移一格）。
        if (dragon.getControllingPassenger() != null) {
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

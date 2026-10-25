package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.Dragon_golems;
import a_silly_cat.dragon_golems.content.config.DragonGolemConfig;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.content.item.card.ConfigCard;
import dev.xkmc.modulargolems.init.data.MGLangData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.entity.PartEntity;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 命令手杖"指定攻击目标"的兜底入口。
 *
 * <h2>为什么需要它</h2>
 * 本家的 {@code CommandWandItem.m_6880_} 有一条分支：
 * <pre>
 * if (target instanceof AbstractGolemEntity golem) {
 *     command(...);              // 打开模式轮盘
 * } else {
 *     m_7579_(stack, target, user); // 扫描玩家周围 32 格的傀儡，resetTarget(target)
 * }
 * </pre>
 * 所以在打"敌人本身也是傀儡"的整合包里（真机就是人形金属傀儡），
 * 右键敌人会被当成"点自己的傀儡" —— 要么打开轮盘、要么直接 FAIL，
 * <b>永远不会走到标记目标那一步</b>。表现就是"命令手杖右键很多次也不能标记敌人"。
 *
 * <p>Forge 的 {@link PlayerInteractEvent.EntityInteract} 在实体交互之前、
 * 物品交互之前派发（和 {@code DragonRideHandler} 用的是同一个时机），
 * 所以这里在"目标不是自己的傀儡"时接管：
 * <ul>
 *   <li>扫描玩家周围 32 格、归属玩家、通过配置卡过滤的傀儡；</li>
 *   <li>对它们调本家的 {@code resetTarget(target)}（会同时写 {@code forcedTarget}）；</li>
 *   <li>发和本家一样的 "N Golems will attack X" 提示；</li>
 *   <li>取消事件，避免本家再按"目标也是傀儡"打开轮盘。</li>
 * </ul>
 *
 * <p>自己的傀儡/自己的宠物仍然原样交给本家（轮盘、curios 等功能不变）。
 */
@Mod.EventBusSubscriber(modid = Dragon_golems.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DragonCommandWandHandler {

    /** 和本家 {@code CommandWandItem.m_7579_} 的扫描半径一致。 */
    private static final double MARK_RANGE = 32.0D;

    private DragonCommandWandHandler() {
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        ItemStack stack = event.getItemStack();
        if (!isCommandWand(stack)) {
            return;
        }
        Player player = event.getEntity();
        Entity target = event.getTarget();
        // 子碰撞箱（头/颈/尾/双翼）折算回本体，和 DragonRideHandler 同一套
        if (target instanceof PartEntity<?> part && part.getParent() instanceof LivingEntity parent) {
            target = parent;
        }
        if (!(target instanceof LivingEntity living)) {
            return;
        }
        // 自己的傀儡：交给本家打开模式轮盘
        if (living instanceof AbstractGolemEntity<?, ?> golem && golem.getOwner() == player) {
            return;
        }
        // 自己的宠物（本家会尝试走 curios 交互）：同样不抢
        if (living instanceof OwnableEntity ownable && ownable.getOwner() == player) {
            return;
        }
        // 客户端只回成功，真正的扫描/写 forcedTarget 在服务端
        if (player.level().isClientSide()) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        int count = 0;
        for (AbstractGolemEntity<?, ?> golem : player.level().getEntitiesOfClass(
                AbstractGolemEntity.class, player.getBoundingBox().inflate(MARK_RANGE))) {
            if (!ConfigCard.getFilter(player).test(golem)) {
                continue;
            }
            if (!golem.canWandModify(player)) {
                continue;
            }
            if (golem.getOwner() != player) {
                continue;
            }
            count++;
            golem.resetTarget(living);
        }

        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.displayClientMessage(
                    MGLangData.CALL_ATTACK.get(count, living.getName()), true);
        }
        if (DragonGolemConfig.pathLog()) {
            Dragon_golems.LOGGER.info("[wand] command target={} markedGolems={}",
                    living.getName().getString(), count);
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    /**
     * 按物品 id 认命令手杖，不按类认。
     *
     * <p>和 {@code DragonGolemEntity.canRideWith} 的理由一样：本家有些构建会把类 jarjar 进别的包；
     * 万能手杖切模式后还会换成另一个已注册物品，所以两种形态都要认。
     */
    private static boolean isCommandWand(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null || !"modulargolems".equals(id.getNamespace())) {
            return false;
        }
        String path = id.getPath();
        return "command_wand".equals(path) || "omnipotent_wand_command".equals(path);
    }
}

package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.Dragon_golems;
import dev.xkmc.l2library.util.raytrace.RayTraceUtil;
import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.init.data.MGConfig;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * 回收手杖的"瞄准辅助"：让本家的回收手杖能<b>点中整条龙</b>，而不是只能点中身体那一小块。
 *
 * <h2>问题是什么</h2>
 * 本家 {@code RetrievalWandItem.use} 自己打一条 64 格的射线，谓词写死成
 * <pre>
 * e -&gt; e instanceof AbstractGolemEntity &amp;&amp; e.canWandModify(user)
 * </pre>
 * 而龙的子碰撞箱（头/颈/躯干/尾/双翼，一共 15 个）是 {@code PartEntity}，
 * <b>不是</b> {@code AbstractGolemEntity} —— 于是它们<b>被整个从候选列表里过滤掉</b>：
 * 看着龙头、龙脖子、龙尾巴、翅膀按下去，射线根本不会命中任何东西（龙的本体判定箱只有 2.6 × 2.2，
 * 挂在身体重心那一块）。玩家的体感就是"这条龙很难选中"，回收自然也就不方便。
 *
 * <h2>怎么补</h2>
 * 在 {@link PlayerInteractEvent.RightClickItem} 里先自己打一条<b>认子箱</b>的射线
 * （子箱一律折回本体 {@link DragonGolemPartEntity#getParent()}），命中龙之后再调用
 * <b>本家自己的</b> {@code ItemStack#interactLivingEntity} ——
 * 也就是 {@code RetrievalWandItem.interactLivingEntity}：
 * 它内部走的是同一个 {@code attemptRetrieve}（配置卡过滤 + {@code canWandModify} + 收进背包），
 * 所以我们<b>一行本家的逻辑都没有复制</b>，只是"替玩家把准星掰到龙身上"。
 *
 * <p>三条保守规则：
 * <ol>
 *   <li><b>本家自己选得中时不抢</b>：先跑一遍本家那条射线，只要它已经能命中任意一个傀儡
 *       （包括龙的身体），就整个放行、什么都不做；</li>
 *   <li><b>潜行时不抢</b>：Shift + 右键是"回收周围所有傀儡"，那条路照旧归本家；</li>
 *   <li><b>只认回收手杖</b>：按物品 id 认（{@code retrieval_wand} 与万能手杖的
 *       {@code omnipotent_wand_retrieval}），别的物品一律不动 —— 和 {@code DragonRideHandler} 同一套写法。</li>
 * </ol>
 *
 * <p>两端都会跑这段（{@code RightClickItem} 客户端也会发），判定完全由世界数据决定，
 * 所以两边结论一致；真正"收进背包"那一步在 {@code attemptRetrieve} 里只对服务端生效
 * （{@code user instanceof ServerPlayer}），客户端只是拿到 SUCCESS 让手感一致。
 */
@Mod.EventBusSubscriber(modid = Dragon_golems.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DragonRetrieveHandler {

    /** 本家回收手杖的 id（{@code GolemItems.RETRIEVAL_WAND} / 万能手杖切到"回收"模式后的那一个）。 */
    private static final String WAND = "retrieval_wand";
    private static final String OMNI_WAND = "omnipotent_wand_retrieval";
    /** 本家配置读不到时的兜底距离，和 {@code MGConfig.COMMON.retrieveDistance} 的默认值一致。 */
    private static final double DEFAULT_DISTANCE = 64.0D;

    private DragonRetrieveHandler() {
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        ItemStack stack = event.getItemStack();
        if (player.isShiftKeyDown() || !isRetrievalWand(stack)) {
            return;
        }
        double distance = retrieveDistance();
        // 本家那条射线已经能选中傀儡（瞄准的是身体、或者别的傀儡）→ 不抢，原样交给它
        if (nativeTraceFindsGolem(player, distance)) {
            return;
        }
        DragonGolemEntity dragon = traceDragon(player, distance);
        if (dragon == null) {
            return;
        }
        // 走本家自己的交互入口：过滤、权限、收进背包、记事本全部照旧
        InteractionResult result = stack.interactLivingEntity(player, dragon, event.getHand());
        if (result == InteractionResult.PASS) {
            return;
        }
        event.setCancellationResult(result);
        event.setCanceled(true);
    }

    /** 手里的是不是本家的回收手杖（普通款 / 万能手杖款）。 */
    private static boolean isRetrievalWand(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        String path = id.getPath();
        return path.equals(WAND) || path.equals(OMNI_WAND);
    }

    /** 本家的回收距离（读不到就用默认值）。 */
    private static double retrieveDistance() {
        try {
            return MGConfig.COMMON.retrieveDistance.get();
        } catch (Throwable e) {
            return DEFAULT_DISTANCE;
        }
    }

    /**
     * 本家那条射线（只认 {@code AbstractGolemEntity}）现在能不能选中什么东西。
     *
     * <p>和 {@code RetrievalWandItem.use} 里的谓词保持一致（{@code canWandModify}），
     * 唯一目的就是"别抢本家已经能干好的活"。
     */
    private static boolean nativeTraceFindsGolem(Player player, double distance) {
        EntityHitResult result = RayTraceUtil.rayTraceEntity(player, distance,
                e -> e instanceof AbstractGolemEntity<?, ?> golem && golem.canWandModify(player));
        return result != null && result.getEntity() instanceof AbstractGolemEntity<?, ?>;
    }

    /**
     * 认子箱的射线：把 {@link DragonGolemPartEntity} 折回本体之后再判定，并沿用原版
     * {@code ProjectileUtil.getEntityHitResult}（它会吃 {@code getPickRadius()}，
     * 也就是配置里的 {@code selection.pickRadius / bodyPickRadius} 在这里同样生效）。
     *
     * <p>这条射线顺手也会跳过"自己骑着的那条龙"：子箱现在如实回答
     * {@code getRootVehicle() == 本体}，而原版那套"同车就别抢准星"的规则正是看它
     * （见 {@link DragonGolemPartEntity#getRootVehicle()}）。
     */
    @Nullable
    private static DragonGolemEntity traceDragon(Player player, double distance) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(distance));
        AABB search = player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, search,
                isRetrievableDragon(), distance * distance);
        return hit == null ? null : dragonOf(hit.getEntity());
    }

    /** 候选实体是不是"一条还没被收走的龙"（子箱也算）。 */
    private static Predicate<Entity> isRetrievableDragon() {
        return e -> {
            DragonGolemEntity dragon = dragonOf(e);
            return dragon != null && !dragon.isRemoved();
        };
    }

    /** 命中的实体是哪条龙（子箱 → 本体；本体 → 本体；别的 → null）。 */
    @Nullable
    private static DragonGolemEntity dragonOf(Entity entity) {
        if (entity instanceof DragonGolemPartEntity part) {
            return part.getParent();
        }
        return entity instanceof DragonGolemEntity dragon ? dragon : null;
    }
}

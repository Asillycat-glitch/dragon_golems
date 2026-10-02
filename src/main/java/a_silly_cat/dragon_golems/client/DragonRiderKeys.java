package a_silly_cat.dragon_golems.client;

import a_silly_cat.dragon_golems.Dragon_golems;
import a_silly_cat.dragon_golems.dragon.DragonGolemEntity;
import a_silly_cat.dragon_golems.network.DragonNetwork;
import a_silly_cat.dragon_golems.network.DragonSkillPacket;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.util.function.Predicate;

/**
 * 骑手的技能键：<b>R = 冲锋、G = 龙息、V = 龙弹</b>（身体是幽匿的龙，第三个键自动走音爆，
 * 和 AI 的槽位规则一致）。三个键都可以在"控制"设置里改，默认值只是 R / G / V。
 *
 * <p><b>只在骑着龙时响应</b>：平时走路、骑别的坐骑完全不碰这三个键，不会和别的 mod 抢。
 *
 * <p>按键在服务端是看不见的（原版只同步跳跃/潜行），所以要自己发包给服务端，
 * 包里带上客户端算好的瞄准点——原因见 {@link DragonSkillPacket} 的说明。
 * 服务端会重新校验一次（射程、冷却、是不是真的在骑），见
 * {@link DragonGolemEntity#onRiderCommand}。
 */
@OnlyIn(Dist.CLIENT)
public final class DragonRiderKeys {

    /** 按键类别名，出现在"控制"设置里。 */
    private static final String CATEGORY = "key.categories.dragon_golems";

    public static final KeyMapping CHARGE = new KeyMapping(
            "key.dragon_golems.charge", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY);
    public static final KeyMapping BREATH = new KeyMapping(
            "key.dragon_golems.breath", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY);
    public static final KeyMapping BLAST = new KeyMapping(
            "key.dragon_golems.blast", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY);

    /**
     * 瞄准射程（格）。取 48：龙弹的射程是 44（{@code SKILL_ROCKET}），
     * 瞄准点没必要比最远的技能还远。
     */
    private static final double AIM_RANGE = 48.0D;
    /** 射线上没打到实体时，最多"贴"到它多近算命中（格）。给点宽容度，高速目标才点得中。 */
    private static final double AIM_PICK_PADDING = 1.0D;

    private DragonRiderKeys() {
    }

    /** 注册到"控制"设置（MOD 事件总线）。 */
    @Mod.EventBusSubscriber(modid = Dragon_golems.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Registration {

        private Registration() {
        }

        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(CHARGE);
            event.register(BREATH);
            event.register(BLAST);
        }
    }

    /**
     * 每 tick 检查三个键（FORGE 事件总线）。
     *
     * <p>用 {@code consumeClick()} 而不是 {@code isDown()}：前者是"刚按下"那个沿，按住不会连发；
     * 连发间隔交给服务端的冷却管（见 {@code DragonRiderSkillGoal}）。
     * 放 {@code ClientTickEvent} 而不是 {@code InputEvent.Key}：后者拿不到"玩家是不是骑着龙"，
     * 而且想在按键回调里做射线检测很容易踩到渲染线程的状态。
     */
    @Mod.EventBusSubscriber(modid = Dragon_golems.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class Ticker {

        private Ticker() {
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                return;
            }
            // 只在骑着龙的时候吃这三个键
            if (!(mc.player.getVehicle() instanceof DragonGolemEntity)) {
                return;
            }
            // 开着界面（背包/聊天）时不算
            if (mc.screen != null) {
                return;
            }
            if (CHARGE.consumeClick()) {
                send(mc, DragonSkillPacket.SKILL_DIVE);
            }
            if (BREATH.consumeClick()) {
                send(mc, DragonSkillPacket.SKILL_BREATH);
            }
            if (BLAST.consumeClick()) {
                send(mc, DragonSkillPacket.SKILL_BLAST);
            }
        }

        private static void send(Minecraft mc, int skill) {
            Vec3 aim = computeAim(mc);
            DragonNetwork.CHANNEL.sendToServer(new DragonSkillPacket(skill, aim.x, aim.y, aim.z));
        }
    }

    /**
     * 算瞄准点：<b>先打方块截断射线、再在射线上找实体、都没中就取射线终点</b>。
     *
     * <p>为什么不能直接用 {@code player.pick(range, ...)}：那个方法内部用玩家属性
     * {@code forge:entity_reach}（默认 <b>3 格</b>）当实体距离闸门，传 48 进去也只打得到 3 格内的东西。
     * 所以按原版那套自己来一遍。
     */
    private static Vec3 computeAim(Minecraft mc) {
        Entity player = mc.player;
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(AIM_RANGE));

        // 方块先截断：龙息不该穿墙
        BlockHitResult blockHit = mc.level.clip(new ClipContext(eye, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 rayEnd = blockHit.getType() == HitResult.Type.MISS ? end : blockHit.getLocation();

        AABB search = player.getBoundingBox().expandTowards(look.scale(AIM_RANGE)).inflate(1.0D);
        Predicate<Entity> filter = candidate -> candidate != player
                && candidate.isPickable()
                && candidate instanceof LivingEntity
                && !candidate.isSpectator();
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                mc.level, player, eye, rayEnd, search, filter, (float) AIM_PICK_PADDING);
        if (entityHit != null) {
            // 命中实体就瞄它的判定箱中心，别瞄"射线与它的交点"——那个点会随着视角抖
            Vec3 center = entityHit.getEntity().getBoundingBox().getCenter();
            return new Vec3(center.x, center.y, center.z);
        }
        return rayEnd;
    }
}

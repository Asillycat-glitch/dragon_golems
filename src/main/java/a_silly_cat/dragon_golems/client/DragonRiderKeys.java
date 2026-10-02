package a_silly_cat.dragon_golems.client;

import a_silly_cat.dragon_golems.Dragon_golems;
import a_silly_cat.dragon_golems.dragon.DragonDebug;
import a_silly_cat.dragon_golems.dragon.DragonGolemEntity;
import a_silly_cat.dragon_golems.network.DragonFreeFlightPacket;
import a_silly_cat.dragon_golems.network.DragonNetwork;
import a_silly_cat.dragon_golems.network.DragonRideInputPacket;
import a_silly_cat.dragon_golems.network.DragonSkillPacket;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
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
     * 下降键。
     *
     * <p><b>默认给左 Ctrl，而不是 Shift。</b>原版 {@code Player.wantsToStopRiding()} 直接返回
     * {@code isShiftKeyDown()}，而 {@code Player.baseTick} 一看到它为真就 {@code stopRiding()}
     * —— <b>Shift 是"下车键"</b>，拿它做下降只会让玩家从龙背上掉下去。
     * 上升键仍然沿用原版跳跃键（那个在服务端本来就有同步）。
     */
    public static final KeyMapping DESCEND = new KeyMapping(
            "key.dragon_golems.descend", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_CONTROL, CATEGORY);

    /**
     * 自由飞行（穿墙）开关。默认 <b>H</b>。
     *
     * <p>为什么需要它：龙的判定箱是 {@code bodyScale × (2.6 宽 × 2.2 高)}，
     * 而原版寻路是按"一格宽的实体"假设的 —— 它以为能过的缝，龙其实挤不过去。
     * 在矿洞、走廊、树丛里表现为概率性撞墙甚至卡死。与其做一堆半吊子的
     * "只穿树叶不穿石头"，不如给一个明确的开关，外加服务端的自动脱困兜底
     * （见 {@code DragonFlightAssist}）。
     */
    public static final KeyMapping FREE_FLIGHT = new KeyMapping(
            "key.dragon_golems.free_flight", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY);

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
            event.register(DESCEND);
            event.register(FREE_FLIGHT);
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
            // 诊断：骑着龙的时候每 40 tick 报一次当前读到的按键状态，
            // 用来区分"Tick 处理器根本没跑"和"跑了但读不到按键"
            if (DragonDebug.RIDE && mc.player.getVehicle() instanceof DragonGolemEntity
                    && mc.player.tickCount % 40 == 0) {
                Dragon_golems.LOGGER.info("[ride] heartbeat flags={} screen={} vehicle={}",
                        readFlags(mc), mc.screen != null,
                        mc.player.getVehicle().getClass().getSimpleName());
            }
            // 原始输入读数：只在骑着龙、且至少按住了一个键的时候每 20 tick 打一次
            // （不然一直刷屏；这样"按住 Ctrl 时到底读到了什么"就能直接看到）
            if (DragonDebug.RIDE && mc.player.getVehicle() instanceof DragonGolemEntity
                    && mc.player.tickCount % 20 == 0 && readFlags(mc) != 0) {
                logRawInput(mc);
            }
            // 骑着龙时：每 tick 把"升降键状态"的变化发出去（只在变化时发，按住不会刷包）
            if (mc.player.getVehicle() instanceof DragonGolemEntity) {
                sendInput(mc);
            } else if (lastSentFlags != 0) {
                // 下龙/换乘之后补一包"全松开"，免得服务端以为键还按着
                lastSentFlags = 0;
                DragonNetwork.CHANNEL.sendToServer(new DragonRideInputPacket(0));
            }
            // 开着界面（背包/聊天）时下面三个技能键不算
            if (mc.screen != null) {
                return;
            }
            if (!(mc.player.getVehicle() instanceof DragonGolemEntity)) {
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
            if (FREE_FLIGHT.consumeClick()) {
                // 目标状态从"龙当前的实际状态"取反，而不是自己维护一个本地布尔：
                // 服务端可能因为自动脱困/其它原因改过它，读实体才不会和实际相反。
                toggleFreeFlight(mc);
            }
        }

        /**
         * 切换自由飞行（穿墙）。
         *
         * <p>读的是实体自己的 {@code isFreeFlight()}（服务端会把它同步回来），
         * 所以本地不需要再存一份状态、也就不存在"两边不一致"的问题。
         */
        private static void toggleFreeFlight(Minecraft mc) {
            if (!(mc.player.getVehicle() instanceof DragonGolemEntity dragon)) {
                return;
            }
            boolean want = !dragon.isFreeFlight();
            DragonNetwork.CHANNEL.sendToServer(new DragonFreeFlightPacket(want));
            if (DragonDebug.RIDE) {
                Dragon_golems.LOGGER.info("[fly] client 请求自由飞行 = {}", want);
            }
        }

        /** 上一次发出去的升降状态（bit0 上升 / bit1 下降），用来做"只在变化时发包"。 */
        private static int lastSentFlags;

        /**
         * 读升降键状态。<b>优先读 {@code player.input}，而不是 {@code KeyMapping}。</b>
         *
         * <p>{@code LocalPlayer.input} 才是原版真正在用的那份输入
         * （{@code Input.jumping} / {@code Input.shiftKeyDown}，由 {@code KeyboardInput.tick()}
         * 每 tick 刷新）；{@code KeyMapping.isDown()} 是"原始按键有没有按住"，
         * 在装了按键重绑类 mod（整合包里就有 Controlling）时可能和实际输入不一致。
         * 两边都读、任一为真就算按下，最稳。
         */
        private static int readFlags(Minecraft mc) {
            boolean up = false;
            boolean down = false;
            if (mc.player instanceof LocalPlayer) {
                // 不写 var local = (LocalPlayer) mc.player：var 局部变量在语言层面是隐式 final，
                // 而模式变量也是隐式 final，两者撞在一起 javac 会报"模式变量不是最终变量"。
                var input = mc.player.input;
                if (input != null) {
                    up = input.jumping;
                    down = input.shiftKeyDown;
                }
            }
            if (!up && mc.options.keyJump.isDown()) {
                up = true;
            }
            // 下降键只认我们注册的那一个（放在"控制"里可改）。
            // 注意 <b>不要</b>再回退去读玩家身上的潜行状态：原版 Shift 是下车键，
            // 把"潜行"当成下降会让玩家一按 Shift 就从龙背上掉下去。
            if (!down && DESCEND.isDown()) {
                down = true;
            }
            int flags = 0;
            if (up) {
                flags |= 1;
            }
            if (down) {
                flags |= 2;
            }
            return flags;
        }

        /**
         * 诊断：每 20 tick 把"升降键的原始读数"打出来。
         *
         * <p>排查"按了键但没反应"必须看到<b>原始输入</b>（{@code input.jumping} /
         * {@code input.shiftKeyDown} / 我们自己的 {@code DESCEND} 键是否按下、
         * 以及它到底绑在哪个键上）——只看合成后的 flags 分不清是"键没读到"还是"逻辑没生效"。
         */
        private static void logRawInput(Minecraft mc) {
            String descendKey = "?";
            try {
                descendKey = DESCEND.getKey().getDisplayName().getString();
            } catch (RuntimeException ignored) {
                // 取不到就算了，不影响诊断的主体
            }
            String jumpKey = "?";
            try {
                jumpKey = mc.options.keyJump.getKey().getDisplayName().getString();
            } catch (RuntimeException ignored) {
            }
            // 不用 `x instanceof LocalPlayer lp` 再赋给 boolean：模式变量是隐式 final，
            // 只要它在 lambda/三元里被再赋值就会报错（这里两次引用都是这个原因）。
            boolean rawJump = false;
            boolean rawSneak = false;
            var input = mc.player.input;
            if (input != null) {
                rawJump = input.jumping;
                rawSneak = input.shiftKeyDown;
            }
            Dragon_golems.LOGGER.info(
                    "[ride] raw input.jumping={} input.shiftKeyDown={} DESCEND.isDown={} jumpKey={} descendKey={} flags={}",
                    rawJump, rawSneak, DESCEND.isDown(), jumpKey, descendKey, readFlags(mc));
        }

        private static void sendInput(Minecraft mc) {
            int flags = readFlags(mc);
            if (flags == lastSentFlags) {
                return;
            }
            lastSentFlags = flags;
            DragonNetwork.CHANNEL.sendToServer(new DragonRideInputPacket(flags));
            if (DragonDebug.RIDE) {
                Dragon_golems.LOGGER.info("[ride] client send input flags={} up={} down={}",
                        flags, (flags & 1) != 0, (flags & 2) != 0);
            }
        }

        private static void send(Minecraft mc, int skill) {
            Aim aim = computeAim(mc);
            DragonNetwork.CHANNEL.sendToServer(new DragonSkillPacket(
                    skill, aim.pos.x, aim.pos.y, aim.pos.z, aim.entityId));
            if (DragonDebug.RIDE) {
                Dragon_golems.LOGGER.info("[ride] client send skill={} aim=({}, {}, {}) entity={}",
                        skill, String.format("%.1f", aim.pos.x), String.format("%.1f", aim.pos.y),
                        String.format("%.1f", aim.pos.z), aim.entityId);
            }
        }
    }

    /** 瞄准结果：一个世界坐标 + 命中的实体 id（{@code -1} = 没命中实体）。 */
    private record Aim(Vec3 pos, int entityId) {
    }

    /**
     * 算瞄准点：<b>先打方块截断射线、再在射线上找实体、都没中就取射线终点</b>。
     *
     * <p>为什么不能直接用 {@code player.pick(range, ...)}：那个方法内部用玩家属性
     * {@code forge:entity_reach}（默认 <b>3 格</b>）当实体距离闸门，传 48 进去也只打得到 3 格内的东西。
     * 所以按原版那套自己来一遍。
     */
    private static Aim computeAim(Minecraft mc) {
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
                // ★ 必须排除"玩家自己骑着的坐骑（含它的子碰撞箱）"：
                //   龙的头部/前颈子碰撞箱本来就伸在玩家正前方，不排除的话准星射线会先命中
                //   龙自己，于是"瞄准点"变成龙头、"命中实体"变成这条龙本身 ——
                //   按 R 冲锋时目标就是自己，整套动作自然起不来。
                //   用 getRootVehicle() 而不是 getVehicle()：真被拖挂在别的载具上时也一并排掉。
                && !candidate.isPassengerOfSameVehicle(player)
                && candidate != player.getRootVehicle()
                && !candidate.isSpectator();
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                mc.level, player, eye, rayEnd, search, filter, (float) AIM_PICK_PADDING);
        if (entityHit != null) {
            // 命中实体就瞄它的判定箱中心，别瞄"射线与它的交点"——那个点会随着视角抖。
            // 同时把实体 id 带回去：冲锋（俯冲）那条航线必须有目标才能起飞，见 DragonSkillPacket.entityId。
            Vec3 center = entityHit.getEntity().getBoundingBox().getCenter();
            if (DragonDebug.RIDE) {
                Dragon_golems.LOGGER.info("[ride] aim hit entity={} class={}",
                        entityHit.getEntity().getName().getString(),
                        entityHit.getEntity().getClass().getSimpleName());
            }
            return new Aim(new Vec3(center.x, center.y, center.z), entityHit.getEntity().getId());
        }
        return new Aim(rayEnd, -1);
    }
}

package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.Dragon_golems;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 玩家驾驶：把"乘客的按键"翻译成龙这一 tick 该有的朝向、速度与俯仰。
 *
 * <p><b>本类刻意接在原版的骑乘管线上，而不是绕开它。</b>1.20.1 的流程是
 * {@code LivingEntity.travel} → {@code travelRidden}（只有 {@code getControllingPassenger()}
 * 是玩家时才会走这条）→ {@code tickRidden} / {@code getRiddenInput} / {@code getRiddenSpeed}
 * → {@code move}。也就是说：<b>只要龙的 {@code getControllingPassenger()} 返回玩家，
 * 原版每 tick 都会问这几个方法</b>，我们只需要回答"往哪飞、多快"，
 * 碰撞、贴墙、上下坡、贴地摩擦全交给原版那套 {@code move}。
 *
 * <p>反过来做（在 {@code aiStep} 里直接写 {@code deltaMovement}）是<b>行不通的</b>：
 * {@code travelRidden} 每一步都会用 {@code moveRelative(getRiddenInput × getRiddenSpeed)} 重写竖直与水平速度，
 * 前面写的会被原版覆盖掉，最后变成"按住 W 也不动"。
 * （本 mod 的悬停/俯冲能直接写速度，是因为那条路上没有玩家在操控、走的是另一条分支。）
 *
 * <p>分工：
 * <ul>
 *   <li>{@link #getRiddenInput}：WASD → 沿机头的前后 + 左右（原版 {@code zza}/{@code xxa}，
 *       这两个量在服务端也有同步，所以联机同样有效）；</li>
 *   <li>{@link #getRiddenSpeed}：飞的快慢（吃材质/升级的移速倍率）；</li>
 *   <li>{@link #tickRidden}：转向（按转速慢慢转，不会瞬间掉头）+ 机体俯仰（跟视角）；</li>
 *   <li>{@link #tickVertical}：空格上升 / Shift 下降 —— 这是唯一在 {@code super.aiStep()} <b>之后</b>
 *       补的一笔：原版骑乘不给竖直输入，而 {@code noGravity} 下没人写 Y 速度就永远是 0。</li>
 * </ul>
 */
public final class DragonRiderControl {

    /**
     * 骑乘时的水平速度（格/tick）：{@code 0.2 × 速度倍率}。
     *
     * <p>参照物是龙自己的巡航速度（{@link DragonIdleGoal} 的 WANDER_SPEED = 0.45）。玩家驾驶要比
     * AI 巡航慢一点才好控，但比原版玩家自己的 0.1 快一倍；幽匿 / 泰坦那类 +移速材料会按
     * {@link DragonGolemEntity#flightSpeedFactor()} 一起放大。
     */
    private static final double RIDER_SPEED = 0.20D;

    /**
     * 升降速度（格/tick）：空格上升、Shift 下降。
     *
     * <p>比水平慢（0.4 倍）：竖直方向和水平一样快的话，按一下就是大起大落，
     * 而且下降速度超过玩家自己的自由落体，看起来不像"飞"而像"砸"。
     */
    private static final double RIDER_VERTICAL = 0.08D;

    /** 竖直速度的收敛系数：空格/Shift 放开后是平移收住，不是一刀切成 0。 */
    private static final double RIDER_VERTICAL_LERP = 0.35D;

    /** 骑乘时的基础转向速度（度/tick）：6°/tick = 120°/秒。
     *
     * <p>不能像原版地面坐骑那样"朝向 = 玩家视角"（那种一帧 360° 都行）：这条龙有 26 格长的身体和
     * 一条 70 格的尾巴，瞬间掉头看起来就是整条龙在原地拧过去。这里复用
     * {@link DragonGolemEntity#turnRateFor}，所以低速是 6°/tick、高速按转弯半径一起涨。
     */
    private static final float RIDER_TURN = 6.0F;

    /**
     * 骑乘时允许的最大机体俯仰（度）：跟着玩家视角抬头/低头，模型和判定箱一起动。
     *
     * <p>夹到 35：原版玩家能看 ±90°，而龙低头 90° 等于整条竖起来，判定箱会跟着立起来。
     */
    private static final float RIDER_PITCH_MAX = 35.0F;
    /** 俯仰平滑系数（每 tick 朝玩家视角靠这么多）。 */
    private static final float RIDER_PITCH_LERP = 0.25F;

    /** 骑乘俯仰的同步字段声明在 {@link DragonGolemEntity}（{@code DATA_RIDER_PITCH}）。 */

    private DragonRiderControl() {
    }

    /** 当前骑乘俯仰（度，正 = 低头）。没人骑时恒为 0。 */
    public static float riderPitch(DragonGolemEntity dragon) {
        return dragon.getRiderPitch();
    }

    /** 正在驾驶这条龙的玩家，没有就返回 null。 */
    @Nullable
    public static Player rider(DragonGolemEntity dragon) {
        return dragon.getControllingPassenger() instanceof Player player ? player : null;
    }

    // ---- 原版每 tick 会问的三个问题 ----

    /**
     * 往哪飞。<b>返回的是"输入方向"（分量约 -1..1），不是速度</b> —— 原版会再乘
     * {@link #riddenSpeed}，所以这里不能把速度算进来，否则会乘多一次。
     *
     * <p>坐标约定和原版 {@code moveRelative} 一致：{@code (x, y, z) = (左, 上, 前)}。
     */
    public static Vec3 riddenInput(Player player) {
        float forward = Mth.clamp(player.zza, -1.0F, 1.0F);
        float strafe = Mth.clamp(player.xxa, -1.0F, 1.0F);
        // 后退慢一点（原版地面坐骑也是这么压的），免得"倒着飞"比正飞还好用
        if (forward < 0.0F) {
            forward *= 0.25F;
        }
        return new Vec3(strafe, 0.0D, forward);
    }

    /** 飞多快：材料 / 升级的移速倍率在这里生效。 */
    public static float riddenSpeed(DragonGolemEntity dragon) {
        return (float) (RIDER_SPEED * dragon.flightSpeedFactor());
    }

    /**
     * 驾驶期间每 tick 调一次（由 {@code DragonGolemEntity.tickRidden} 转发）：转向 + 俯仰。
     *
     * <p>原版 {@code tickRidden} 是 {@code travelRidden} 里"算速度之前"那一步，所以在这里
     * 改 {@code yRot} / {@code yBodyRot}，同 tick 的 {@code moveRelative} 就已经用上新朝向了，
     * 不会出现"速度朝旧方向、机头已经转过去"的错帧。
     */
    public static void tickBody(DragonGolemEntity dragon, Player player) {
        // ★ 玩家自己按 R 要的冲锋是"驾驶期间只认玩家"这条规则的<b>例外</b>：
        //   那几秒的控制权归 DragonDiveGoal 的航线，这里绝不能去拆它的台。
        //   下面三行（清速度 / 清目标 / 清姿态）原本是无条件的，于是把骑手冲锋整个抹掉：
        //   goal 明明 start() 了、冷却也扣了，但每 tick 都被清成
        //   diveVel=false / dive=0 / target=null —— 真机日志里就是
        //   "dive ordered 成功、y 却卡在 -47.23 纹丝不动、dive 恒为 0"。
        if (!dragon.isRiderOrderedDive()) {
            // 别的 goal 写下的速度（俯冲/巡航）一律作废：驾驶期间只有玩家说话算数
            dragon.setDiveVelocity(null);
            if (dragon.getDivePhase() != DragonGolemEntity.DIVE_PHASE_NONE) {
                dragon.setDivePhase(DragonGolemEntity.DIVE_PHASE_NONE);
            }
        }
        if (dragon.isIdleSettled()) {
            // 一上人就把它从"原地贴地待机"里叫醒
            dragon.setIdleSettled(false);
        }
        // 驾驶期间不打人：玩家在操作时被自己龙的技能抢走控制权会很难受。
        // 同理，冲锋期间必须留着自己那条目标，否则航线立刻失去目标。
        if (!dragon.isRiderOrderedDive()) {
            dragon.setTarget(null);
        }

        // ---- 机头朝向：朝玩家视角的 yaw 慢慢转 ----
        Vec3 look = player.getLookAngle();
        double horizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        if (horizontal > 1.0E-4D) {
            float yaw = (float) (Mth.atan2(look.z, look.x) * 180.0D / Math.PI) - 90.0F;
            float delta = Mth.wrapDegrees(yaw - dragon.getYRot());
            // 上限取 max(基础转速, 按转弯半径算出来的转速的一半)：高速时不会"锁死在 6°/tick"，
            // 但也不会像 faceMovement 那样一 tick 直接把机头拧过去。
            float cap = Math.max(RIDER_TURN, dragon.turnRateFor(
                    Math.max(RIDER_SPEED, dragon.getDeltaMovement().horizontalDistance())) * 0.5F);
            dragon.setYRot(dragon.getYRot() + Mth.clamp(delta, -cap, cap));
            dragon.yRotO = dragon.getYRot();
            // 身体 / 头立刻跟上：子碰撞箱是按 yBodyRot 摆的，晚一 tick 就会"准星在龙身上、
            // 判定箱还在旧方向"。头也钉住，免得 LookAtPlayer 那套把脖子扭过去。
            dragon.yBodyRot = dragon.getYRot();
            dragon.yBodyRotO = dragon.yBodyRot;
            dragon.setYHeadRot(dragon.getYRot());
            dragon.yHeadRotO = dragon.yHeadRot;
        }

        // ---- 机体俯仰：跟着视角，模型和子碰撞箱一起低头/抬头（两边读同一个同步值） ----
        // 符号和 pitchForPhase 一致：正 = 低头，而原版 getXRot() 也是正 = 向下看。
        // 注意这里<b>不动实体自己的 xRot</b>：乘客的相机姿态是客户端拿乘客自己的 xRot 算的，
        // 服务端改它会白白搅乱那一份平滑状态。龙的抬头低头只走 DATA_RIDER_PITCH。
        float wantPitch = Mth.clamp(player.getXRot(), -RIDER_PITCH_MAX, RIDER_PITCH_MAX);
        dragon.setRiderPitch(Mth.lerp(RIDER_PITCH_LERP, riderPitch(dragon), wantPitch));
    }

    /** 诊断用：{@code loggedVertical} 保证"读到升降键"这条只打一次，不刷屏。 */
    private static boolean loggedVertical;

    /**
     * 竖直方向：上升键 / 下降键。
     *
     * <p><b>必须在 {@code super.aiStep()} 之后调</b>：原版骑乘只管水平（{@code moveRelative}
     * 的输入向量里 y 分量没人给），而 {@code noGravity} 下没人写 Y 速度就等于永远不升降；
     * 写早了又会被 {@code travelRidden} 里那一步冲掉。
     *
     * <p><b>这两个键的状态为什么不能直接读玩家身上的 {@code jumping} / {@code isShiftKeyDown}：</b>
     * Shift 是原版的<b>下车键</b>——{@code Player.wantsToStopRiding()} 直接返回
     * {@code isShiftKeyDown()}，一按就 {@code stopRiding()}，拿去当下降会让人从龙背上掉下去；
     * 而 {@code Player} 上的那个方法是 protected、龙覆写不了（试过，编译不过）。
     * 所以升降状态改由客户端通过 {@code DragonRideInputPacket} 发过来，
     * 上升键默认仍绑原版跳跃键、下降键默认绑左 Ctrl。
     */
    public static void tickVertical(DragonGolemEntity dragon, Player player) {
        // 骑手冲锋期间竖直速度归航线管（DragonDiveGoal 经 diveVelocity 写），
        // 这里再写一次会把它按回悬停高度 —— 那正是"按 R 只看见姿态、龙不落地"的另一半原因。
        if (dragon.isRiderOrderedDive()) {
            dragon.fallDistance = 0.0F;
            return;
        }
        double want = 0.0D;
        if (dragon.riderWantsUp()) {
            want = RIDER_VERTICAL;
        } else if (dragon.riderWantsDown()) {
            want = -RIDER_VERTICAL;
        }
        // 诊断：第一次真的读到"上升/下降"时打一行，确认服务端这侧的数据是通的
        if (DragonDebug.RIDE && want != 0.0D && !loggedVertical) {
            loggedVertical = true;
            Dragon_golems.LOGGER.info("[ride] server applying vertical want={} (up={} down={})",
                    want, dragon.riderWantsUp(), dragon.riderWantsDown());
        }
        want = clampVertical(dragon, want);
        Vec3 current = dragon.getDeltaMovement();
        double vy = Mth.lerp(RIDER_VERTICAL_LERP, current.y, want);
        if (Math.abs(vy) < 1.0E-4D) {
            vy = 0.0D;
        }
        dragon.setDeltaMovement(current.x, vy, current.z);
        // 自己写速度 → 必须告诉原版"我不在下落"，否则骑乘时会被判摔落伤害
        dragon.fallDistance = 0.0F;
    }

    /**
     * 竖直速度的安全闸门：<b>不许把龙开进地形</b>。
     *
     * <p>玩家驾驶时没有悬停那套自动纠高，所以"按着 Shift 不放"会一路钻下去。
     * 这里只夹"低于地面多少"和"头顶顶到方块"，不做自动悬停 —— 想停在哪个高度是玩家自己的事。
     */
    private static double clampVertical(DragonGolemEntity dragon, double vy) {
        if (vy < 0.0D) {
            // 拿机体高度当最低离地量：贴到地面就停住，不再往下钻
            double minY = groundHeight(dragon) + dragon.getBbHeight();
            if (dragon.getY() + vy <= minY) {
                vy = Math.max(0.0D, minY - dragon.getY());
            }
        } else if (vy > 0.0D) {
            // 头顶有东西就别硬顶（矿洞 / 屋里），和 applyHover 是一个思路
            BlockPos above = BlockPos.containing(
                    dragon.getX(), dragon.getY() + dragon.getBbHeight() + 0.5D, dragon.getZ());
            if (!dragon.level().getBlockState(above).isAir()) {
                vy = 0.0D;
            }
        }
        return vy;
    }

    private static double groundHeight(DragonGolemEntity dragon) {
        return dragon.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mth.floor(dragon.getX()), Mth.floor(dragon.getZ()));
    }

    /** 下龙之后把驾驶帧留下的俯仰收回水平（否则龙会一直歪着）。 */
    public static void relax(DragonGolemEntity dragon) {
        float current = riderPitch(dragon);
        if (current == 0.0F) {
            return;
        }
        float next = Mth.lerp(0.2F, current, 0.0F);
        dragon.setRiderPitch(Math.abs(next) < 0.05F ? 0.0F : next);
    }
}

package a_silly_cat.dragon_golems.dragon;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * 龙的待机移动：取代本家的 {@code FollowOwnerGoal}（它把导航终点设成主人的脚底，而龙被悬停顶在
 * 离地 {@code HOVER_HEIGHT} 格，于是永远"到不了"却一直占着 MOVE 通道 —— 表现就是 XZ 完全不移动）。
 *
 * <p>行为照本家傀儡的直觉来，但换成飞行版：
 * <ul>
 *   <li><b>WANDER</b>：在一个范围内<b>随机游走</b>——每隔一段时间在"软中心"（低通跟随主人的位置）
 *       周围随机采样一个水平目标点，飞过去，到了立刻换下一个点，所以<b>一直在动、不会停在原地</b>；</li>
 *   <li><b>RETURN</b>：水平离主人超过 {@code RETURN_START} 时，直接朝主人（上方悬停高度）飞回来，
 *       回到 {@code RETURN_STOP} 以内再恢复随机游走（带滞回，不会"刚追上又散开"来回抽）。</li>
 * </ul>
 *
 * <p><b>关键点：目标点是"采样"出来的世界坐标，不是每帧从主人身上重建的。</b>
 * 上一版每帧用"主人位置 + 圆周偏移"算目标点，主人一动目标点就整格跳、速度跟着跳，
 * 看起来就是一顿一顿；现在主人移动只会让"软中心"慢慢跟着走，龙继续飞自己那个点，
 * 只有在超出活动范围（{@code WANDER_RADIUS}）时才重新采样。
 *
 * <p>高度始终是"脚下地形 + {@code HOVER_HEIGHT} + 呼吸"，所以山上、悬崖边都贴着地貌走
 * （到达判定只看水平，竖向永远差着 HOVER_HEIGHT 格，用三维距离会永远"没到"）。
 * <b>"脚下地形"现在把水面与岩浆面也算进去</b>（见 {@code DragonGolemEntity.localFloorY()}），
 * 所以湖上悬停 = 悬在液面之上，不会一头扎进水里；随机目标点也尽量避开液面
 * （见 {@link #sampleWaypoint()}）。
 */
public class DragonIdleGoal extends Goal {

    /**
     * 随机活动范围：以软中心为圆心的水平半径（格），<b>会乘速度倍率</b>（见 {@link #rangeScale}）。
     * 上限受传送阈值约束：离主人水平超过 {@code DragonFollowTeleportGoal.MAX_HORIZONTAL × 体型} 会被传送回来。
     * 1 倍速度下实际最远 ≈ 软中心滞后（跟着走的玩家约 4~6 格）+ 本半径 + 一点松绳余量，
     * 所以 24 是留足余量的选择；速度 3 倍时会放大到 72。
     */
    private static final double WANDER_RADIUS = 24.0D;
    /** 采样新目标点时，离软中心至少这么远（避免原地打转）。 */
    private static final double WANDER_MIN_RADIUS = 5.0D;
    /**
     * 采样时最多重采几次来避开液体（水面 / 岩浆面）。
     *
     * <p>见 {@link #sampleWaypoint()}：全是液体才认账用最后一个，所以次数不用多。
     */
    private static final int DRY_TRIES = 8;
    /** 水平走到这么近就算到了，立刻换下一个点。 */
    private static final double WANDER_REACH = 2.5D;
    /** 同一个目标点最多追这么久（tick），追不到就换（防卡地形）。 */
    private static final int WANDER_TIMEOUT = 140;
    /** 巡游速度与回位速度（格/tick）。 */
    private static final double WANDER_SPEED = 0.45D;
    private static final double RETURN_SPEED = 0.70D;
    /** 跟踪增益、最低速度（最低速度保证不会"到点刹停"）、水平总限速。 */
    private static final double GAIN = 0.25D;
    private static final double MIN_SPEED = 0.12D;
    private static final double HORIZONTAL_MAX = 1.0D;
    /** 回位滞回：超过 START 开始回位，回到 STOP 以内恢复游走。 */
    private static final double RETURN_START = 24.0D;
    private static final double RETURN_STOP = 12.0D;
    /** 软中心（低通跟随主人）的速度，0.08 ≈ 12 tick 追上。 */
    private static final double CENTER_LERP = 0.08D;
    /** 目标点离软中心超过 半径 × 这个倍数 就重新采样（主人走远了）：24 × 1.3 ≈ 31 格，仍离传送阈值有余量。 */
    private static final double LEASH_SLACK = 1.3D;
    /** 竖向追踪增益与上限（比水平小，免得忽上忽下）。 */
    private static final double VERTICAL_GAIN = 0.12D;
    private static final double VERTICAL_MAX = 0.18D;
    /** 上下呼吸：高度在 [基准, 基准 + BOB_RANGE] 之间缓慢起伏。 */
    private static final double BOB_RANGE = 1.5D;
    /** 呼吸周期（tick，320 = 16 秒一次）。 */
    private static final double BOB_PERIOD = 320.0D;
    /**
     * 没有战斗目标连续这么久（tick）就"沉降"成原地悬停待机：600 tick = 30 秒。
     *
     * <p>注意计时<b>不是</b>"主人有没有移动"：攻击/追击结束（{@code getTarget() == null}）之后开始数，
     * 满 30 秒龙就停在原地不动了，主人只是绕着它走不会把它叫醒。
     */
    private static final int SETTLE_TICKS = 600;

    private final DragonGolemEntity dragon;
    /** 低通之后的"软中心"（只用 x/z）。第一次用原始位置初始化，避免从原点插值把龙甩出去。 */
    @Nullable
    private Vec3 softCenter;
    /** 没有跟随目标时用的固定锚点。 */
    @Nullable
    private Vec3 anchor;
    /** 当前要飞过去的随机目标点（水平坐标有意义，y 不参与）。 */
    @Nullable
    private Vec3 waypoint;
    private int waypointTicks;
    private boolean returning;
    private float bobOffset;
    /** 是否已经沉降（原地悬停待命，不再随机游走）。 */
    private boolean settled;
    private int settleTicks;

    public DragonIdleGoal(DragonGolemEntity dragon) {
        this.dragon = dragon;
        // 只占 MOVE：朝向交给 LookAtPlayerGoal / RandomLookAroundGoal，别抢
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    /**
     * 活动范围、到达判定、回位距离、竖向速度上限随速度放大的倍率。
     *
     * <p>{@link DragonIdleGoal} 里所有"格"都是死写的常量，而水平速度会乘
     * {@code flightSpeedFactor()}（幽匿 +0.5 移速 → 1.5 倍；泰坦 +100% 移速 → 2 倍；
     * 两个叠起来顶到 3 倍上限）。速度涨了这些长度不涨，就会出现：
     * <ul>
     *   <li>{@link #WANDER_REACH}(2.5 格) 比一 tick 飞过的距离还短 → 每个目标点一 tick 就冲过去、
     *       立刻重采样，龙在原地附近一直打转，"游荡范围过小"；</li>
     *   <li>{@link #WANDER_RADIUS}(24 格) 相对 3 格/tick 太小 → 刚起飞就要掉头；</li>
     *   <li>{@link #VERTICAL_MAX}(0.18 格/tick) 相对水平太慢 → 高速时爬升永远追不上地形。</li>
     * </ul>
     * 1 倍速度时返回 1，所以基准配置的手感一点不变。
     */
    private double rangeScale() {
        return this.dragon.speedScale();
    }

    @Override
    public boolean canUse() {
        return this.movable();
    }

    @Override
    public boolean canContinueToUse() {
        return this.movable();
    }

    @Override
    public void start() {
        this.bobOffset = this.dragon.getRandom().nextFloat() * (float) BOB_PERIOD;
        this.softCenter = null;
        this.waypoint = null;
        this.waypointTicks = 0;
        this.returning = false;
        this.settled = false;
        this.settleTicks = 0;
        this.dragon.setIdleSettled(false);
    }

    @Override
    public void tick() {
        // 俯冲/攻击动作期间让位：那些阶段自己写速度
        if (this.dragon.getDivePhase() != DragonGolemEntity.DIVE_PHASE_NONE) {
            return;
        }
        // 本 tick 的活动范围倍率（速度越快，半径/到达判定/回位距离/竖向速度一起放大）
        double range = this.rangeScale();

        // ---- 软中心：低通跟随主人（或巡逻点/锚点） ----
        Vec3 raw = this.rawCenter();
        if (this.softCenter == null) {
            this.softCenter = new Vec3(raw.x, this.dragon.getY(), raw.z);
        } else {
            this.softCenter = new Vec3(
                    Mth.lerp(CENTER_LERP, this.softCenter.x, raw.x),
                    this.softCenter.y,
                    Mth.lerp(CENTER_LERP, this.softCenter.z, raw.z));
        }

        // ---- 回位判定（带滞回） ----
        double distToCenter = this.horizontalDistance(this.dragon.getX(), this.dragon.getZ(),
                this.softCenter.x, this.softCenter.z);

        // ---- 沉降：没有目标满 30 秒就地悬停待机；出现目标 / 又被打 / 主人跑出活动范围立刻唤醒 ----
        if (this.dragon.getTarget() != null || this.dragon.hurtTime > 0) {
            this.settled = false;
            this.settleTicks = 0;
        } else if (this.settled) {
            // 主人跑到该回位的距离（RETURN_START）之外才唤醒：只是绕圈走不打断待机
            if (distToCenter > RETURN_START * range) {
                this.settled = false;
                this.settleTicks = 0;
            }
        } else if (++this.settleTicks >= SETTLE_TICKS) {
            this.settled = true;
        }
        // 告诉实体"现在是不是原地待机"：只有这一档才把悬停高度降到离地 3 格（见 hoverHeight）
        this.dragon.setIdleSettled(this.settled);
        if (this.settled) {
            // 沉降状态：水平完全不动，钉死在待机高度（3 格）。这里刻意不加呼吸起伏 ——
            // "离地三格"是个给以后骑乘用的固定高度，上下浮动会让人站不稳；想要那点浮动的话
            // 把下面 wantY 末尾的 + this.bob() 加回来即可。
            this.waypoint = null;
            // 用 hoverTargetY() 而不是自己再算一遍：那个方法会把目标压到"头顶天花板之下"
            // （洞里的龙否则会朝世界地表高度一直爬，实测过：surface=70 / y=-6 → 目标 80）。
            double settleY = this.dragon.hoverTargetY();
            double settleVy = Mth.clamp((settleY - this.dragon.getY()) * VERTICAL_GAIN,
                    -VERTICAL_MAX * range, VERTICAL_MAX * range);
            this.dragon.setDiveVelocity(new Vec3(0.0D, settleVy, 0.0D));
            return;
        }

        if (!this.returning && distToCenter > RETURN_START * range) {
            this.returning = true;
            this.waypoint = null;
        } else if (this.returning && distToCenter < RETURN_STOP * range) {
            this.returning = false;
            this.waypoint = null;
        }

        // ---- 选一个目标点 ----
        double targetX;
        double targetZ;
        double maxSpeed;
        if (this.returning) {
            // 回到主人身边（水平就是主人位置，高度另有悬停逻辑）
            targetX = this.softCenter.x;
            targetZ = this.softCenter.z;
            maxSpeed = RETURN_SPEED;
        } else {
            this.waypointTicks++;
            boolean needNew = this.waypoint == null
                    || this.waypointTicks > WANDER_TIMEOUT
                    || this.horizontalDistance(this.dragon.getX(), this.dragon.getZ(),
                    this.waypoint.x, this.waypoint.z) < WANDER_REACH * range
                    || this.horizontalDistance(this.softCenter.x, this.softCenter.z,
                    this.waypoint.x, this.waypoint.z) > WANDER_RADIUS * range * LEASH_SLACK;
            if (needNew) {
                this.waypoint = this.sampleWaypoint();
                this.waypointTicks = 0;
            }
            targetX = this.waypoint.x;
            targetZ = this.waypoint.z;
            maxSpeed = WANDER_SPEED;
        }

        // ---- 水平速度：朝目标点，限速但有最低速度（保证一直在动） ----
        // 巡航/回位速度都要乘"飞行速度倍率"，材料与速度升级的移速加成在这里生效
        double factor = this.dragon.flightSpeedFactor();
        double dx = targetX - this.dragon.getX();
        double dz = targetZ - this.dragon.getZ();
        double dh = Math.sqrt(dx * dx + dz * dz);
        double vx = 0.0D;
        double vz = 0.0D;
        if (dh > 0.05D) {
            double speed = Mth.clamp(dh * GAIN, MIN_SPEED * factor, maxSpeed * factor);
            vx = dx / dh * speed;
            vz = dz / dh * speed;
        }
        double horizontal = Math.sqrt(vx * vx + vz * vz);
        double horizontalMax = HORIZONTAL_MAX * factor;
        if (horizontal > horizontalMax) {
            vx = vx / horizontal * horizontalMax;
            vz = vz / horizontal * horizontalMax;
        }

        // ---- 高度：地表高度 + 悬停高度 + 呼吸，但被头顶天花板夹住 ----
        // 用 hoverTargetY() 而不是自己拼：它会 min(世界地表+悬停高度, 天花板下一格)。
        // 自己拼的话，洞里的龙会朝"世界地表 + 10"一直爬（实测 surface=70 / y=-6 → 目标 80），
        // 表现为无限向上飞直到被回位传送拽回来。
        double wantY = this.dragon.hoverTargetY() + this.bob();
        double vy = Mth.clamp((wantY - this.dragon.getY()) * VERTICAL_GAIN,
                -VERTICAL_MAX * range, VERTICAL_MAX * range);
        // 蹭到方块就抬一点，免得贴着树、墙原地磨
        if (this.dragon.horizontalCollision) {
            vy = Math.max(vy, 0.15D);
        }

        // 主动避障：撞墙时不再只是"往上顶 0.15"，而是在扇面里找真正塞得下龙的方向。
        // 原实现只有上面那一行，在矿洞/树丛里表现为"贴着同一面墙磨很久"
        // （龙判定箱 2.6 格宽，寻路按一格宽假设，它以为能过的缝其实挤不过去）。
        Vec3 want = new Vec3(vx, vy, vz);
        Vec3 adjusted = this.dragon.flightAssist().avoidance(this.dragon, want);
        this.dragon.setDiveVelocity(adjusted);
    }

    @Override
    public void stop() {
        this.dragon.setDiveVelocity(null);
        this.dragon.setIdleSettled(false);
    }

    private boolean movable() {
        return this.dragon.isMovable()
                && !this.dragon.isInSittingPose()
                && !(this.dragon.getControllingPassenger() instanceof Player);
    }

    /**
     * 在软中心周围随机采一个水平目标点（离圆心 [WANDER_MIN_RADIUS, WANDER_RADIUS] × 速度倍率 格）。
     *
     * <p><b>★ 优先采"脚下不是液体"的点</b>（水面 / 岩浆面）。龙现在已经不会沉进液体里了
     * （见 {@code DragonGolemEntity.localFloorY()}），但一条几十格长的龙整天在湖面上悬停、
     * 在岩浆湖上飘着，既难看也不像"在自己地盘上巡逻"。
     * 连采 {@link DRY_TRIES} 次都是液体（主人就站在湖心小岛、岩浆湖边）才认账用最后一个 ——
     * 那种情况悬停高度也在液面之上，不会扎进去。
     */
    private Vec3 sampleWaypoint() {
        double range = this.rangeScale();
        assert this.softCenter != null;
        Vec3 last = null;
        for (int i = 0; i < DRY_TRIES; i++) {
            double a = this.pickAngle();
            double radius = (WANDER_MIN_RADIUS
                    + this.dragon.getRandom().nextDouble() * (WANDER_RADIUS - WANDER_MIN_RADIUS)) * range;
            last = new Vec3(
                    this.softCenter.x + Math.cos(a) * radius,
                    this.dragon.getY(),
                    this.softCenter.z + Math.sin(a) * radius);
            if (!this.dragon.isLiquidFloorAt(Mth.floor(last.x), Mth.floor(last.z))) {
                return last;
            }
        }
        return last;
    }

    /**
     * 采样方向：尽量不选"跟自己当前航向差 120° 以上"的点（那样每次换点都变成原地掉头，
     * 看起来就是"撞到边界才开始慢慢转"）。最多试 4 次，实在不行就用最后一个。
     */
    private double pickAngle() {
        Vec3 motion = this.dragon.getDeltaMovement();
        double heading = Math.sqrt(motion.x * motion.x + motion.z * motion.z) > 0.02D
                ? Math.atan2(motion.z, motion.x)
                : this.dragon.getRandom().nextDouble() * Math.PI * 2.0D;
        double best = Double.NaN;
        for (int i = 0; i < 4; i++) {
            double a = this.dragon.getRandom().nextDouble() * Math.PI * 2.0D;
            best = a;
            if (Math.abs(Mth.wrapDegrees(Math.toDegrees(a - heading))) <= 120.0D) {
                return a;
            }
        }
        return best;
    }

    /**
     * 原始中心：<b>自由行动时是"原点"</b>，其余情况跟主人（或守卫点、巡逻点），只取水平坐标；
     * 没有跟随目标时（{@code getTargetPos()} 返回自己的位置）用固定锚点。
     *
     * <p><b>自由行动为什么特殊：</b>本家的 {@code FREE_WANDER} 是
     * {@code new GolemMode(false, true, true, ...)}，{@code positioned = false} ——
     * 所以 {@code getTargetPos()} 对它返回的是<b>主人位置</b>，也就是本家的"自由行动"
     * 其实是"跟着主人到处走"。用户要的是"收回原点 + 自行追逐攻击"，
     * 所以这个模式下改成围绕 {@code freeWanderOrigin()} 游走、回位也回原点。
     */
    private Vec3 rawCenter() {
        if (this.dragon.isFreeWander()) {
            Vec3 origin = this.dragon.freeWanderOrigin();
            this.anchor = origin;
            return origin;
        }
        Vec3 follow = this.dragon.getTargetPos();
        if (follow.distanceToSqr(this.dragon.position()) < 1.0D) {
            if (this.anchor == null) {
                this.anchor = this.dragon.position();
            }
            return this.anchor;
        }
        this.anchor = follow;
        return follow;
    }

    /**
     * 高度参照系 —— 转发龙的"局部地面"。
     *
     * <p><b>参数 {@code x}/{@code z} 现在被忽略了</b>（保留签名是为了不改调用点）。
     * 原来这里是 {@code getHeight(MOTION_BLOCKING_NO_LEAVES, x, z)}，也就是<b>世界地表</b>；
     * 龙在矿洞里时它读到 70 而龙在 y=22 → "悬停目标 80"→ 无限向上爬。
     * 参照系必须和龙自己所在的那一层有关（见 {@code DragonGolemEntity.localFloorY()}）。
     *
     * <p>注意本类里真正参与决策的高度已经全部走 {@code dragon.hoverTargetY()} 了，
     * 这个方法现在只被采样/诊断路径用到 —— 保留它并把语义改正，避免以后有人再踩同一个坑。
     */
    private double groundHeight(double x, double z) {
        return this.dragon.localFloorY();
    }

    private double horizontalDistance(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 上下呼吸偏移（0 ~ {@link #BOB_RANGE}）。 */
    private double bob() {
        double phase = (this.dragon.tickCount + this.bobOffset) * (Math.PI * 2.0D) / BOB_PERIOD;
        return BOB_RANGE * 0.5D * (1.0D - Math.cos(phase));
    }
}

package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.Dragon_golems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 龙的俯冲：<b>"舔地手术刀"式的一次掠过（strafing pass）</b>。
 *
 * <p>整段只有两个阶段：
 * <ol>
 *   <li><b>LINEUP</b>：<b>向前</b>飞到航线入口（目标后方 {@link #PASS_BACK}(16) 格、离地
 *       {@link #ENTRY_ALT}(12) 格）。注意是"向前"——入口永远在龙和目标的连线上，
 *       龙离得近时入口就跟着近（不会像上一版那样先倒退 10 格再冲）。</li>
 *   <li><b>PASS</b>：沿一条固定的弧线从头飞到尾，<b>全程不再换挡</b>：
 *       位置 = 目标 + 前进方向 × s，高度按 s 走一条"两端高、中间贴地"的平滑曲线
 *       （{@code u^1.7} 的缓入缓出，所以在掠过目标那一下切线是水平的，不会像上一版
 *       铲地结束时突然抬头）。速度只在弧线上从 {@link #ENTRY_SPEED}(1.05) 平滑加到
 *       {@link #PASS_SPEED}(1.85)：入口慢、命中附近最快、拉升时再慢下来 —— 全程向前，
 *       中间最快。</li>
 * </ol>
 *
 * <p>伤害：整段每 tick 扫一次收窄的伤害盒（拿上一 tick 的盒子做扫掠，所以高速也不会穿过去），
 * 每个目标第一次接触按 {@link #IMPACT_MULT}(1.5×，带横扫)、之后每 {@link #HIT_COOLDOWN}(10) tick
 * 补一次 {@link #PLOW_MULT}(0.35×)。所以轨迹上"中间（命中点）伤害最高，两端只有擦伤"。
 *
 * <p>进入 PASS 的那一 tick 就已经把落点朝向锁死（不追着目标转），所以不会出现"冲一半侧身看人"。
 */
public class DragonDiveGoal extends Goal {

    private enum Phase {
        /** 向前飞到航线入口。 */
        LINEUP,
        /** 掠过（不换挡的一整段弧线）。 */
        PASS
    }

    /** 诊断开关：打印每次俯冲的几何信息。默认关。 */
    private static final boolean DEBUG = false;

    // 触发距离和冷却都交给 DragonGolemEntity 的"掷骰子调度"：这里只负责"被抽中时怎么打"。

    // ---- 航线 ----
    // 下面这几条全是「1 倍体型」的基准值，实际用 passBack()/passForward()/entryAlt()/skimHeight()。
    // 为什么要按体型放大：泰坦升级（+300% 体型 = 4 倍）之后龙身一百多格长，
    // 而原来整条航线（后退 16 + 前进 20 = 36 格）比龙身还短，等于"原地转一圈"，
    // 而且 SKIM_HEIGHT(1.2) 会让模型最低点（原点下方约 1.4 × 体型）直接埋进土里。
    /** 从目标往后退这么多格开始俯冲（龙太近时会自动缩短，绝不倒退）。 */
    private static final double PASS_BACK = 16.0D;
    /** 掠过目标之后再往前这么多格完成拉升。 */
    private static final double PASS_FORWARD = 20.0D;
    /** 掠过目标时的离地高度（越低越"舔地"）。 */
    private static final double SKIM_HEIGHT = 1.2D;
    /** 拉伸高度时，模型最低点在原点下方多少个"体型单位"（和 DragonGolemEntity.MODEL_LIFT 那套同源）。 */
    private static final double MODEL_DROP_PER_SCALE = 1.5D;
    /** 入口/出口的离地高度。 */
    private static final double ENTRY_ALT = 12.0D;
    /** 高度曲线的指数：>1 = 中间更平（掠过那一下切线水平）、两端更快地抬起来。 */
    private static final double ALT_EXP = 1.7D;
    /** 入口速度 / 命中附近的速度（格/tick），一路上平滑过渡。 */
    private static final double ENTRY_SPEED = 1.05D;
    private static final double PASS_SPEED = 1.85D;
    /** 速度上限（材料/升级的移速倍率拉满时也不至于变成瞬移）。 */
    private static final double SPEED_CAP = 3.0D;
    /** 入口点到达判定。 */
    private static final double LINEUP_REACH = 2.5D;
    private static final int LINEUP_MAX = 160;
    private static final int PASS_MAX = 200;
    /** 瞄准点往航线前方看这么多 tick（跟随弧线的提前量）。 */
    private static final double LOOKAHEAD_TICKS = 2.0D;

    // ---- 伤害 ----
    private static final float IMPACT_MULT = 1.5F;
    private static final double IMPACT_KNOCKBACK = 0.8D;
    private static final float PLOW_MULT = 0.35F;
    private static final double PLOW_KNOCKBACK = 0.3D;
    /** 每个目标两次结算之间至少隔这么久（= 原版受击无敌窗）。 */
    private static final int HIT_COOLDOWN = 10;
    /**
     * 接触盒向外扩多少（格，<b>1 倍基准</b>，实际乘体型）。
     * 高速掠过时更容易判定到；下面 {@link #contactBox()} 还会再往下铺到模型最低点。
     */
    private static final double CONTACT_INFLATE = 0.75D;

    private final DragonGolemEntity dragon;
    private Phase phase = Phase.LINEUP;
    private int phaseTicks;
    /** 这一轮攻击是否已经跑完（PASS 结束）。 */
    private boolean finished;
    /** 水平前进方向（进入 PASS 之前就锁定）。 */
    @Nullable
    private Vec3 dir;
    /** 掠过目标时的期望高度（绝对 Y）。 */
    private double skimY;
    /** 航线参数：相对目标的带符号距离（负 = 还没到目标）。 */
    private double passS;
    /** 航线起点：把参数换算成绝对坐标用的目标水平位置。 */
    @Nullable
    private Vec3 targetAt;
    /** 上一 tick 的接触盒，用来做扫掠判定。 */
    @Nullable
    private AABB sweepBox;
    private final Set<Integer> impacted = new HashSet<>();
    private final Map<Integer, Integer> nextHit = new HashMap<>();
    private boolean damaged;

    public DragonDiveGoal(DragonGolemEntity dragon) {
        this.dragon = dragon;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ---- 按体型缩放的航线尺寸 ----
    // 这一组是"这个 goal 里所有长度单位"的唯一出口，改体型相关的手感就调它们。

    /** 航线几何倍率（= 龙体型；缩小时按 1）。 */
    private double geo() {
        return this.dragon.combatGeoScale();
    }

    /** 俯冲起点在目标后方多少格（4 倍体型 16 → 64）。 */
    private double passBack() {
        return PASS_BACK * this.geo();
    }

    /** 掠过之后再往前多少格完成拉升（航线必须长过龙身，否则不叫"掠过"）。 */
    private double passForward() {
        return PASS_FORWARD * this.geo();
    }

    /**
     * 入口 / 出口的离地高度。
     *
     * <p>跟<b>悬停高度</b>走，不跟着体型线性放大：悬停高度本身是开方曲线（4 倍体型 → 20 格），
     * 线性放大能给到 48 格 —— 比龙自己的巡航高度还高，俯冲就变成"从天上掉下来"了。
     */
    private double entryAlt() {
        return Math.max(ENTRY_ALT, this.dragon.hoverHeight() + 2.0D);
    }

    /**
     * 掠过目标时的离地高度。
     *
     * <p>必须保证模型不入地：模型最低点在原点下方约 {@code 1.5 × 体型} 格，
     * 所以 4 倍体型下至少要抬到 5.7 格（原来死写 1.2，等于整个模型埋进土里 4 格多）。
     */
    private double skimHeight() {
        return SKIM_HEIGHT + MODEL_DROP_PER_SCALE * (this.geo() - 1.0D);
    }

    @Override
    public boolean canUse() {
        // 诊断：<b>事件窗口</b>而不是时间采样。
        // 之前写成 `tickCount % 20 == 0`，但 pendingSkill 只存活一 tick（goal 一被选中就被清），
        // 那个窗口和"每 20 tick"撞上的概率极低 —— 结果一条日志都没打出来，等于没诊断。
        // 现在改成"从骑手指令被受理的那一刻起，记录 40 tick 内每一次 canUse 的判定"。
        boolean trace = DragonDebug.RIDE && this.dragon.inRiderDiveTraceWindow();
        // 只有"这一轮被骰子抽中"时才上（冷却由实体统一管，见 DragonGolemEntity.tickSkillRoll）。
        // 骑手指令（按 R）也是走这条：DragonGolemEntity.onRiderCommand → orderDive() 会把
        // pendingSkill 手动置成 DIVE，所以这里同样能过。
        if (!this.dragon.wantsSkill(DragonGolemEntity.DragonSkill.DIVE)) {
            if (trace) {
                Dragon_golems.LOGGER.info("[dive] canUse=false: pendingSkill={} (不是 DIVE)",
                        this.dragon.pendingSkill());
            }
            return false;
        }
        if (!this.dragon.isMovable() || this.dragon.isInSittingPose()) {
            if (trace) {
                Dragon_golems.LOGGER.info("[dive] canUse=false: movable={} sitting={}",
                        this.dragon.isMovable(), this.dragon.isInSittingPose());
            }
            return false;
        }
        // ★ 有人骑着时：只放行"骑手指令的冲锋"，仍然挡掉 AI 自己抽中的那一次。
        //   原来这里无条件 return false，把按 R 的冲锋也一起挡死了。
        if (this.dragon.getControllingPassenger() != null && !this.dragon.isRiderOrderedDive()) {
            if (trace) {
                Dragon_golems.LOGGER.info("[dive] canUse=false: 有乘客但不是骑手指令 (riderOrdered={})",
                        this.dragon.isRiderOrderedDive());
            }
            return false;
        }
        LivingEntity target = this.dragon.getTarget();
        if (target == null || !target.isAlive()) {
            if (trace) {
                Dragon_golems.LOGGER.info("[dive] canUse=false: 目标为空或已死 target={} forced={}",
                        target, this.dragon.forcedTarget);
            }
            return false;
        }
        double dist = this.horizontalDistance(this.dragon.getX(), this.dragon.getZ(), target.getX(), target.getZ());
        boolean ok = dist >= this.dragon.diveMinH() && dist <= this.dragon.diveMaxH();
        if (trace) {
            Dragon_golems.LOGGER.info("[dive] canUse={} dist={} allowed={}..{} riderOrdered={} target={}",
                    ok, String.format("%.1f", dist),
                    String.format("%.1f", this.dragon.diveMinH()),
                    String.format("%.1f", this.dragon.diveMaxH()),
                    this.dragon.isRiderOrderedDive(), target.getName().getString());
        }
        return ok;
    }

    /**
     * 有人骑上来就<b>立刻中断</b>这一轮俯冲。
     *
     * <p>{@link #canUse()} 里已经挡了乘客，但那只管"能不能开始"——{@code canContinueToUse} 原来只看
     * {@code finished}，所以玩家在龙俯冲途中骑上去时，这一整套 LINEUP→DIVE→PLOW→CLIMB 会继续跑完
     * （最多几秒），期间控制权还在 goal 手里，玩家会觉得"骑着骑着它自己冲出去了"。
     *
     * <p>这里调用 {@link #finish()}（而不是直接 return false）是故意的：那条路会走到
     * {@link #stop()}，把 {@code diveVelocity}、姿态阶段清干净并上报 {@code onSkillFinished}，
     * 所以不会留下"悬在半空的俯冲状态"。
     *
     * <p><b>★ 这条守卫当初漏了一半：</b>它无条件中断"有乘客"的情况，于是把<b>玩家自己按 R 的冲锋</b>
     * 也一起打断了 —— 表现是"canUse 通过、dive ordered 成功、冷却扣了 600，但龙一个 tick 就被中断、
     * 完全不动"。真机日志（14:40:59~14:41:15）就是这个序列：骑着时指令受理但龙 flat 在 y=-44.50，
     * 玩家一下龙它才真的冲下去。所以这里和 {@code canUse} 保持一致：
     * <b>只中断 AI 自己抽中的那一次，放行骑手指令</b>。玩家随时可以按键取消/接管。
     */
    @Override
    public boolean canContinueToUse() {
        if (this.dragon.getControllingPassenger() != null && !this.dragon.isRiderOrderedDive()) {
            this.finish();
            return false;
        }
        return !this.finished;
    }

    @Override
    public void start() {
        this.finished = false;
        // 告诉调度器"这一轮真的开打了"：俯冲整套（LINEUP→DIVE→PLOW→CLIMB）远超 60 tick 的重掷窗口
        this.dragon.onSkillStarted(DragonGolemEntity.DragonSkill.DIVE);
        this.impacted.clear();
        this.nextHit.clear();
        this.sweepBox = null;
        this.damaged = false;
        this.dir = null;
        this.passS = 0.0D;
        this.targetAt = null;
        this.enter(Phase.LINEUP);
        this.dragon.setAggressive(true);
    }

    @Override
    public void stop() {
        this.dragon.setDiveVelocity(null);
        this.dragon.setDivePhase(DragonGolemEntity.DIVE_PHASE_NONE);
        this.dragon.setAggressive(false);
        // 通知调度器：这一轮俯冲打完了（开始下一轮的间隔计时）
        this.dragon.onSkillFinished(DragonGolemEntity.DragonSkill.DIVE);
        if (DEBUG) {
            Dragon_golems.LOGGER.info("[dive] 结束 damaged={} s={}", this.damaged, String.format("%.1f", this.passS));
        }
        this.impacted.clear();
        this.nextHit.clear();
    }

    @Override
    public void tick() {
        LivingEntity target = this.dragon.getTarget();
        this.phaseTicks++;
        // 用 if/else 而不是 enum switch：enum 的 statement switch 会让 javac 生成一个合成类
        // DragonDiveGoal$1（$SwitchMap）。那个类一旦因为任何原因（比如游戏运行中替换 jar）
        // 加载不到，就会在实体 tick 里抛 NoClassDefFoundError 直接崩档。少一个合成类少一个坑。
        if (this.phase == Phase.LINEUP) {
            this.tickLineup(target);
        } else {
            this.tickPass(target);
        }
    }

    /** 向前飞到航线入口，顺手把方向定下来。 */
    private void tickLineup(@Nullable LivingEntity target) {
        if (target == null || !target.isAlive()) {
            this.finish();
            return;
        }
        if (this.dir == null) {
            this.dir = this.horizontalDir(this.dragon.getX(), this.dragon.getZ(), target.getX(), target.getZ());
        }
        Vec3 entry = this.entryPoint(target);
        double speed = ENTRY_SPEED * this.speedFactor();
        this.dragon.setDiveVelocity(this.steer(entry, speed));
        if (this.dragon.position().distanceTo(entry) < LINEUP_REACH || this.phaseTicks > LINEUP_MAX) {
            this.enter(Phase.PASS);
        }
    }

    /**
     * 掠过：沿锁定的弧线飞。
     *
     * <p>参数 s 不是"每 tick 加一个常数"，而是<b>拿龙在航线上的投影</b>来推进
     * （只会前进、不会后退）。这样做的两个好处：不管龙从哪一点切入，它都会真的沿弧线跑完，
     * 不会出现"目标点在前面不停跑、龙永远追不上"；而瞄准点取 s 再往前一点（
     * {@link #LOOKAHEAD_TICKS} tick 的提前量），跟上弧线靠的是几何本身，不需要分阶段换挡。
     */
    private void tickPass(@Nullable LivingEntity target) {
        double proj = this.projectOnCurve();
        this.passS = Mth.clamp(Math.max(this.passS, proj), -this.passBack(), this.passForward());
        double speed = this.speedAt(this.passS);
        Vec3 aim = this.curvePoint(Math.min(this.passS + speed * LOOKAHEAD_TICKS, this.passForward()));
        this.dragon.setDiveVelocity(this.steer(aim, speed));
        this.scanContact();
        this.passEffects();
        // 姿态：还没到目标 = 低头俯冲（SKIM），过了目标 = 抬头拉升（PULL）
        this.dragon.setDivePhase(this.passS < 0.0D
                ? DragonGolemEntity.DIVE_PHASE_SKIM
                : DragonGolemEntity.DIVE_PHASE_PULL);
        if (this.passS >= this.passForward() || this.phaseTicks > PASS_MAX || this.dragon.horizontalCollision) {
            this.finish();
        }
    }

    /** 龙在航线上投影出来的带符号距离（负 = 还没到目标）。 */
    private double projectOnCurve() {
        if (this.dir == null || this.targetAt == null) {
            return this.passS;
        }
        double dx = this.dragon.getX() - this.targetAt.x;
        double dz = this.dragon.getZ() - this.targetAt.z;
        return dx * this.dir.x + dz * this.dir.z;
    }

    // ---- 航线几何 ----

    /** 入口点：目标后方 {@code back} 格、离地 ENTRY_ALT 格。龙太近时 back 自动缩短（绝不倒退）。 */
    private Vec3 entryPoint(LivingEntity target) {
        assert this.dir != null;
        double dist = this.horizontalDistance(this.dragon.getX(), this.dragon.getZ(), target.getX(), target.getZ());
        double back = Mth.clamp(dist - 2.0D, 0.0D, this.passBack());
        this.recordTarget(target);
        return this.curvePoint(-back);
    }

    /**
     * 弧线上的一个点：{@code 目标 + 方向 × s}，高度 = 两端 {@link #entryAlt()}、中间贴地的平滑曲线。
     *
     * <p>中间那个高度取 {@code max(目标脚下地形 + skimHeight(), 目标的脚 Y)}，
     * 所以打飞行目标时弧线会从它所在的高度穿过，而不是扎到地上。
     */
    private Vec3 curvePoint(double s) {
        assert this.dir != null && this.targetAt != null;
        double x = this.targetAt.x + this.dir.x * s;
        double z = this.targetAt.z + this.dir.z * s;
        double span = s < 0.0D ? this.passBack() : this.passForward();
        double u = Mth.clamp(Math.abs(s) / span, 0.0D, 1.0D);
        double ue = Math.pow(u, ALT_EXP);
        double edge = this.groundHeight(x, z) + this.entryAlt();
        double y = this.skimY + (edge - this.skimY) * ue;
        return new Vec3(x, y, z);
    }

    /** 记下"这一轮瞄准的是谁/哪个点"，弧线就围着它展开。 */
    private void recordTarget(LivingEntity target) {
        if (this.targetAt != null) {
            return;
        }
        this.targetAt = new Vec3(target.getX(), target.getY(), target.getZ());
        this.skimY = Math.max(this.groundHeight(target.getX(), target.getZ()) + this.skimHeight(),
                target.getY());
    }

    /** 沿弧线的速度：入口最慢、命中附近最快、拉升时再慢下来。 */
    private double speedAt(double s) {
        double span = s < 0.0D ? this.passBack() : this.passForward();
        double u = Mth.clamp(Math.abs(s) / span, 0.0D, 1.0D);
        double ease = 1.0D - u * u;                 // s=0 时 1、两端 0
        double base = ENTRY_SPEED + (PASS_SPEED - ENTRY_SPEED) * ease;
        return Math.min(SPEED_CAP, base * this.speedFactor());
    }

    private double speedFactor() {
        return this.dragon.flightSpeedFactor();
    }

    private Vec3 horizontalDir(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4D) {
            float yaw = (float) Math.toRadians(this.dragon.getYRot());
            return new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
        }
        return new Vec3(dx / len, 0.0D, dz / len);
    }

    // ---- 伤害 ----

    /**
     * 这一 tick 的"撞击体积"。
     *
     * <p><b>不能直接用本体判定箱。</b>判定箱只从实体原点<b>往上</b>长，而模型有大半挂在原点
     * <b>下方</b>（腿、翼、尾，最低点约原点下方 {@code 1.4 × 体型} 格）。
     * 1 倍体型贴地飞（离地 1.2 格）时判定箱还勉强够得着地面目标；泰坦体型就不行了 ——
     * 铲地高度被抬到 5.7 格以后，判定箱整个飘在目标头顶
     * （4 倍体型 = [地面+5.7, 地面+14.5]，而人偶只有 2 格高），擦过去什么都撞不到。
     *
     * <p>所以按体型把接触盒<b>向下铺到模型最低点</b>、四周再外扩一点：
     * 向下 {@code MODEL_DROP_PER_SCALE × 体型}、四周 {@code CONTACT_INFLATE × 体型}。
     * 1 倍体型下向下多 1.5 格（模型本来就在那儿），四周仍是 0.75，手感没变。
     */
    private AABB contactBox() {
        double geo = this.geo();
        return this.dragon.getBoundingBox()
                .inflate(CONTACT_INFLATE * geo)
                .expandTowards(0.0D, -MODEL_DROP_PER_SCALE * geo, 0.0D);
    }

    /**
     * 接触结算：每个目标本次掠过的第一次接触按撞击倍率（走横扫管线），
     * 之后每 {@link #HIT_COOLDOWN} tick 补一次擦伤。
     */
    private void scanContact() {
        AABB box = this.contactBox();
        if (this.sweepBox != null) {
            box = box.minmax(this.sweepBox);
        }
        this.sweepBox = this.contactBox();
        // 诊断：每 10 tick 报一次接触盒的垂直范围，用来判断"掠过了但没有伤害"到底是
        // 接触盒够不到目标（几何问题），还是目标被过滤掉了（判定问题）。
        if (DragonDebug.RIDE && this.phaseTicks % 10 == 0) {
            Dragon_golems.LOGGER.info("[dive] contactBox y={}..{} dragonY={} s={} damaged={}",
                    String.format("%.1f", box.minY), String.format("%.1f", box.maxY),
                    String.format("%.1f", this.dragon.getY()),
                    String.format("%.1f", this.passS), this.damaged);
        }
        for (LivingEntity other : this.dragon.level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (other == this.dragon || !other.isAlive() || other.isSpectator()) {
                continue;
            }
            if (this.dragon.hasPassenger(other) || other.isPassengerOfSameVehicle(this.dragon)) {
                continue;
            }
            if (DragonDebug.RIDE) {
                Dragon_golems.LOGGER.info("[dive] candidate {} predicate={} canAttack={} y={}",
                        other.getName().getString(), this.dragon.predicateTarget(other),
                        this.dragon.canAttack(other), String.format("%.1f", other.getY()));
            }
            if (!this.dragon.predicateTarget(other) || !this.dragon.canAttack(other)) {
                continue;
            }
            int id = other.getId();
            this.damaged = true;
            if (this.impacted.add(id)) {
                boolean hit = this.dragon.diveImpact(other, IMPACT_MULT, IMPACT_KNOCKBACK);
                if (DragonDebug.RIDE) {
                    Dragon_golems.LOGGER.info("[dive] IMPACT on {} hit={}", other.getName().getString(), hit);
                }
                this.nextHit.put(id, this.dragon.tickCount + HIT_COOLDOWN);
                this.impactEffects(other);
                continue;
            }
            Integer next = this.nextHit.get(id);
            if (next == null || this.dragon.tickCount >= next) {
                this.dragon.diveGraze(other, PLOW_MULT, PLOW_KNOCKBACK);
                this.nextHit.put(id, this.dragon.tickCount + HIT_COOLDOWN);
            }
        }
    }

    private void impactEffects(LivingEntity target) {
        this.dragon.level().playSound(null, this.dragon.blockPosition(), SoundEvents.ENDER_DRAGON_FLAP,
                SoundSource.HOSTILE, 1.6F, 0.7F);
        if (this.dragon.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.EXPLOSION, target.getX(), target.getY() + target.getBbHeight() * 0.5D,
                    target.getZ(), 5, 0.6D, 0.4D, 0.6D, 0.0D);
        }
    }

    /** 掠过表现：只有贴地那一半才有尘屑（拉升高空后就不该再冒土了）。 */
    private void passEffects() {
        if (!(this.dragon.level() instanceof ServerLevel level) || this.passS > 4.0D) {
            return;
        }
        if (this.phaseTicks % 3 == 0) {
            for (double side = -1.0D; side <= 1.0D; side += 1.0D) {
                double x = this.dragon.getX() + side * 1.3D;
                double z = this.dragon.getZ() + side * 1.3D;
                BlockPos below = BlockPos.containing(x, this.dragon.getY() - 0.3D, z);
                var state = level.getBlockState(below);
                if (state.isAir()) {
                    continue;
                }
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                        x, this.dragon.getY() + 0.2D, z, 6, 0.4D, 0.2D, 0.4D, 0.1D);
            }
        }
        if (this.phaseTicks % 10 == 0) {
            level.playSound(null, this.dragon.blockPosition(), SoundEvents.ENDER_DRAGON_FLAP,
                    SoundSource.HOSTILE, 1.0F, 1.4F);
        }
    }

    // ---- 杂项 ----

    /** 朝一个点飞，靠近时自然减速（不超过 {@code speed}）。 */
    private Vec3 steer(Vec3 want, double speed) {
        Vec3 delta = want.subtract(this.dragon.position());
        double len = delta.length();
        if (len < 1.0E-4D) {
            return Vec3.ZERO;
        }
        return delta.scale(Math.min(speed, len) / len);
    }

    private double groundHeight(double x, double z) {
        return this.dragon.level()
                .getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
    }

    private double horizontalDistance(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void finish() {
        this.finished = true;
        this.stop();
    }

    private void enter(Phase next) {
        this.phase = next;
        this.phaseTicks = 0;
        this.sweepBox = null;
        if (next == Phase.LINEUP) {
            this.dragon.setDivePhase(DragonGolemEntity.DIVE_PHASE_APPROACH);
            return;
        }
        // 进入掠过：把参数从入口开始，并锁定落点（之后不再追着目标转）
        if (this.targetAt == null) {
            LivingEntity target = this.dragon.getTarget();
            if (target != null) {
                this.recordTarget(target);
            }
        }
        double dist = 0.0D;
        LivingEntity target = this.dragon.getTarget();
        if (target != null) {
            dist = this.horizontalDistance(this.dragon.getX(), this.dragon.getZ(), target.getX(), target.getZ());
        }
        this.passS = -Mth.clamp(dist - 2.0D, 0.0D, this.passBack());
        this.dragon.setDivePhase(DragonGolemEntity.DIVE_PHASE_SKIM);
    }
}

package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.Dragon_golems;
import a_silly_cat.dragon_golems.content.config.DragonGolemConfig;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * 龙的远程攻击（方案 C）：<b>先飞到位，再按距离换弹型</b>。
 *
 * <ul>
 *   <li><b>CLOSE_IN</b>：目标还在射程外 / 隔着东西 → 保持悬停高度飞过去。这一段不占冷却，
 *       所以"打不着就靠过去"永远不会被 12 秒 CD 卡住（上一版只会原地干等，看起来就是"丢了攻击能力"）。</li>
 *   <li><b>≤ {@code dragon.breathRange()}（1 倍体型 24 格，泰坦 4 倍约 45 格）：龙息</b>——
 *       从嘴部喷一道锥形喷流（长 {@code dragon.combatGeoScale() × }{@link #CONE_LENGTH}(30)、
 *       半角 {@link #CONE_HALF_ANGLE}(16°)），锥内敌人每 {@link #HIT_COOLDOWN}(10) tick 结算一次，天然是群体伤害。</li>
 *   <li><b>喷息距离 ~ {@link #ROCKET_RANGE}(44) 格：龙弹</b>——嘴部射出
 *       {@link DragonGolemFireball}，落点范围伤害 + 一片龙息云。</li>
 * </ul>
 *
 * <p>喷息/发射期间水平速度锁 0（只维持离地高度），瞄准每 tick 最多转 {@link #AIM_TURN}(6°)、
 * 身体一起转，所以是"悬停炮台扭脖子"，不会像俯冲那样甩头。
 * 优先级 3：低于俯冲（2）、高于待机（4）；俯冲一旦可用会直接打断喷息。
 */
public class DragonRangedGoal extends Goal {

    private enum Phase {
        /** 飞向目标（射程外或没视线时）。 */
        CLOSE_IN,
        /** 喷息前摇：抬头、嘴里聚粒子。 */
        BREATH_CHARGE,
        /** 喷息中。 */
        BREATH,
        /** 喷息收尾。 */
        BREATH_RECOVER,
        /** 龙弹瞄准（张口的短前摇）。 */
        ROCKET_AIM,
        /** 龙弹收尾。 */
        ROCKET_RECOVER
    }

    /** 诊断开关：打印每次进入的阶段和选择原因。默认关。 */
    private static final boolean DEBUG = false;

    // 冷却和"这一轮打哪个技能"都由 DragonGolemEntity 的掷骰子调度决定，这里只负责执行。

    // ---- 距离档位（三维距离）----
    // 喷息的闸门<b>不再是常数</b>：它必须 ≥ 绕圈半径（否则"头越过目标 → 向后喷"），
    // 而绕圈半径又跟着体型涨，所以下面一律用 this.dragon.breathRange()。
    /** 喷息够不着、但还在视线内就用龙弹。 */
    private static final double ROCKET_RANGE = DragonGolemEntity.SKILL_ROCKET;
    /** 超过这个距离先飞过去（再远就是"追"了）。 */
    private static final double CHASE_RANGE = 64.0D;

    // ---- 靠拢 ----
    private static final double CLOSE_SPEED = 0.55D;
    private static final int CLOSE_MAX = 200;

    // ---- 喷息参数 ----
    /**
     * 喷息时的移动方式：
     * <ul>
     *   <li>{@code true} = <b>边飞边喷</b>：绕着目标画圈（切线速度 + 一点径向修正把距离拉回
     *       {@code dragon.orbitRadius()}），同时身子一直对着目标、压一点弯，像炮艇扫射；</li>
     *   <li>{@code false} = 原地悬停喷（上一版的写法）。</li>
     * </ul>
     * 切成 {@code false} 就只有这一个开关的差别。
     */
    private static final boolean BREATH_WHILE_FLYING = true;
    /** 绕圈速度（格/tick，会乘飞行速度倍率）。 */
    private static final double ORBIT_SPEED = 0.55D;
    /** 绕圈时压弯多少度（正负跟着绕行方向走；看着反了就把这个值取负）。 */
    private static final float ORBIT_BANK = 20.0F;
    private static final int CHARGE_TICKS = 20;
    private static final int BREATH_TICKS = 60;
    private static final int RECOVER_TICKS = 10;
    /**
     * 锥体长度（格，<b>1 倍体型基准</b>）。
     *
     * <p>必须明显大于"嘴 → 目标"的距离：龙悬停在离地 10 格、打 20 格开外的地面目标时，
     * 那条线是 √(20² + 10²) ≈ 22.4 格 —— 原来取 18 就会在半空中断掉，表现是"偶尔喷不到地上"。
     *
     * <p>实际长度见 {@link #coneLength()}：泰坦那种 4 倍体型会放到 120 格，
     * 否则一条 30 格的火焰从 100 格长的龙嘴里出来会显得很短。
     */
    private static final double CONE_LENGTH = 30.0D;
    /** 每 tick 沿射线喷多少"段"（每段 2 颗）。这是粒子浓度的主旋钮。 */
    private static final int STREAM_SAMPLES = 26;
    /** 锥体半角（度）。 */
    private static final double CONE_HALF_ANGLE = 16.0D;
    /** 每跳伤害 = ATTACK_DAMAGE × 这个倍率（60 tick 里约 6 跳，单体合计约 2.1 倍攻击力）。 */
    private static final float BREATH_MULT = 0.35F;
    private static final double BREATH_KNOCKBACK = 0.12D;
    /** 同一个目标两次结算之间至少隔这么久（= 原版受击无敌窗）。 */
    private static final int HIT_COOLDOWN = 10;
    /** 瞄准转速的下限（度/tick）。实际转速见 {@link #aim}：会按"绕圈角速度"一起涨。 */
    private static final float AIM_TURN = 6.0F;

    // ---- 龙弹参数 ----
    private static final int ROCKET_AIM_TICKS = 10;
    private static final int ROCKET_RECOVER_TICKS = 10;
    /** 出膛的随机散布，别让每发都走同一条线。 */
    private static final float ROCKET_SPREAD = 0.02F;

    private final DragonGolemEntity dragon;
    private Phase phase = Phase.CLOSE_IN;
    private int phaseTicks;
    /** 跑完一整轮就置 true，让 GoalSelector 收手（和俯冲 goal 同一个套路）。 */
    private boolean finished;
    /**
     * 这一轮<b>真的开火</b>用的是哪个技能（null = 这一趟只是靠拢，什么都没打出去）。
     * {@link #stop()} 只上报它 —— 不能在那里把龙息和龙弹都上报，否则"只飞过去"的一趟
     * 会把抽中的大招白扣一次冷却。
     */
    private DragonGolemEntity.DragonSkill executed;
    /** 每个目标下一次可结算的 tick（避免一帧融化）。 */
    private final Map<Integer, Integer> nextHit = new HashMap<>();
    /** 本轮绕圈的方向（每次开喷随机翻一次，免得永远往同一边转）。 */
    private int orbitSign = 1;

    public DragonRangedGoal(DragonGolemEntity dragon) {
        this.dragon = dragon;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!this.dragon.isMovable() || this.dragon.isInSittingPose()) {
            return false;
        }
        // ★ 幽灵模式（noPhysics：洞穴穿墙 / 自动脱困）允许继续开火：
        //   它本来就要靠穿墙离开死角，这里再按"嵌在方块里"一刀切会让它永远不攻击。
        //   喷流的几何判定本来就会被方块截断（见 breathReach），不会隔着墙结算。
        if (this.dragon.flightAssist().isEmbedded() && !this.dragon.isGhostFlight()) {
            return false;
        }
        if (this.dragon.getControllingPassenger() instanceof Player) {
            return false;
        }
        LivingEntity target = this.dragon.dragonCurrentTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        DragonGolemEntity.DragonSkill skill = this.dragon.pendingSkill();
        if (skill == DragonGolemEntity.DragonSkill.BREATH || skill == DragonGolemEntity.DragonSkill.ROCKET) {
            // 这一轮被骰子抽中了：上去开火（具体用喷息还是龙弹在 start() 里按距离定）
            return this.dragon.distanceTo(target) <= CHASE_RANGE;
        }
        if (skill != null) {
            // 这一轮抽中的是俯冲 / 音爆：走位交给各自的 goal（DragonDiveGoal / DragonSonicGoal），
            // 别在这里抢 MOVE —— 虽然优先级能抢过来，但每 tick 起停一次是白费功夫。
            return false;
        }
        // ★ 没被抽中时：只要目标在喷息射程内 + 有视线，就直接开喷。
        //   龙息本来就是"唯一没有冷却的常态攻击"，等掷骰只会变成"站着挨打不还手"
        //   （真机日志：pending=null 占 43/59，期间完全无输出）。
        if (this.dragon.distanceTo(target) <= this.dragon.breathRange()
                && this.dragon.hasLineOfSight(target)) {
            return true;
        }
        // 够不着/没视线：这一趟只负责飞过去（纯走位，不吃出手间隔）
        return true;
    }

    /**
     * 有人骑上来就立刻中断这一轮（龙息前摇 / 龙弹瞄准 / 纯靠拢都算）。
     *
     * <p>理由和 {@code DragonDiveGoal.canContinueToUse} 一样：{@link #canUse()} 只挡"开始"，
     * 挡不住"已经跑起来的"。这里走 {@link #finish()} 而不是直接 return false，
     * 是为了让 {@link #stop()} 把姿态、投射物与技能状态收拾干净。
     */
    @Override
    public boolean canContinueToUse() {
        if (this.dragon.getControllingPassenger() != null) {
            this.finish();
            return false;
        }
        return !this.finished;
    }

    @Override
    public void start() {
        this.finished = false;
        this.executed = null;
        this.nextHit.clear();
        LivingEntity target = this.dragon.dragonCurrentTarget();
        if (target == null) {
            this.finished = true;
            return;
        }
        DragonGolemEntity.DragonSkill skill = this.dragon.pendingSkill();
        double dist = this.dragon.distanceTo(target);
        boolean los = this.dragon.hasLineOfSight(target);
        // ★ 洞穴里"太近"先不进入前摇：先退到安全站位再打。
        //   旧版只要进射程 + 有视线就直接开始（20 tick 前摇 + 60 tick 喷息全程站桩），
        //   而幽灵穿墙能在一瞬间贴到脸上 —— 这就是"贴上去挨揍"的直接来源。
        boolean tooClose = this.dragon.inCave() && !this.dragon.caveAttackDistanceOk(target);
        if (tooClose) {
            this.enter(Phase.CLOSE_IN);
        } else if (skill == DragonGolemEntity.DragonSkill.BREATH
                && dist <= this.dragon.breathRange() && los) {
            // 每次开喷随机挑一个绕行方向（顺时针/逆时针），免得永远往同一边转
            this.orbitSign = this.dragon.getRandom().nextBoolean() ? 1 : -1;
            this.dragon.onSkillStarted(skill);
            this.enter(Phase.BREATH_CHARGE);
        } else if (skill == DragonGolemEntity.DragonSkill.ROCKET && dist <= ROCKET_RANGE && los) {
            this.dragon.onSkillStarted(skill);
            this.enter(Phase.ROCKET_AIM);
        } else if (!tooClose && dist <= this.dragon.breathRange() && los) {
            // ★ 没抽中技能（pending=null）但已经能打到 → 直接喷息，不再空等掷骰
            this.orbitSign = this.dragon.getRandom().nextBoolean() ? 1 : -1;
            this.enter(Phase.BREATH_CHARGE);
        } else {
            // 条件不满足 → 这一趟只负责飞过去 / 退到安全距离
            this.enter(Phase.CLOSE_IN);
        }
        this.dragon.setAggressive(true);
    }

    @Override
    public void stop() {
        this.dragon.setDiveVelocity(null);
        this.dragon.setDivePhase(DragonGolemEntity.DIVE_PHASE_NONE);
        // 动画收尾（下一轮喷息会重新 begin）
        this.breathAnim.end();
        this.dragon.setAggressive(false);
        // 一轮喷息结束：把"同一次龙息里每个目标挨了几跳"的计数清掉（递减状态，见 beginBreathVolley）
        this.dragon.endBreathVolley();
        // 只上报"这一轮真的开火了"的技能：靠拢那趟（CLOSE_IN）什么都没打出去，
        // 不能在这里把两个技能都上报 —— 那会把抽中的大招白扣一次冷却。
        if (this.executed != null) {
            this.dragon.onSkillFinished(this.executed);
            this.executed = null;
        }
        this.nextHit.clear();
    }

    @Override
    public void tick() {
        // ★ 状态行：回答"它这个时候在干嘛"。每 40 tick 一行，带上阶段与相对高度。
        if (DragonGolemConfig.pathLog() && this.dragon.tickCount % 40 == 0) {
            LivingEntity dbg = this.dragon.dragonCurrentTarget();
            double standoff = dbg != null && this.dragon.inCave()
                    ? this.dragon.caveStandoff(dbg) : -1.0D;
            Dragon_golems.LOGGER.info(
                    "[goal] phase={} phaseTicks={} target={} dist={} y={} tgtY={} dy={} cave={} standoff={} ghost={}",
                    this.phase, this.phaseTicks,
                    dbg == null ? "null" : dbg.getName().getString(),
                    dbg == null ? "-" : String.format("%.1f", this.dragon.distanceTo(dbg)),
                    String.format("%.1f", this.dragon.getY()),
                    dbg == null ? "-" : String.format("%.1f", dbg.getY()),
                    dbg == null ? "-" : String.format("%.1f", this.dragon.getY() - dbg.getY()),
                    this.dragon.inCave(),
                    String.format("%.1f", standoff),
                    this.dragon.isGhostFlight());
        }
        LivingEntity target = this.dragon.dragonCurrentTarget();
        if (target == null || !target.isAlive()) {
            this.finish();
            return;
        }
        this.phaseTicks++;
        // if/else 代替 enum switch：避免 javac 生成 DragonRangedGoal$1 那个 $SwitchMap 合成类
        // （合成类加载不到会直接 NoClassDefFoundError 崩档，比如游戏运行中替换 jar 的时候）。
        if (this.phase == Phase.CLOSE_IN) {
            this.tickCloseIn(target);
        } else if (this.phase == Phase.BREATH_CHARGE) {
            this.tickCharge(target);
        } else if (this.phase == Phase.BREATH) {
            this.tickBreath(target);
        } else if (this.phase == Phase.BREATH_RECOVER) {
            this.tickRecover(RECOVER_TICKS, target);
        } else if (this.phase == Phase.ROCKET_AIM) {
            this.tickRocketAim(target);
        } else {
            this.tickRecover(ROCKET_RECOVER_TICKS, target);
        }
    }

    // ---- 各阶段 ----

    /**
     * 飞向目标：<b>只有这一轮真抽中了对应技能</b>才切去开火。
     *
     * <p>这里原来只看距离和视线，不看 {@code pendingSkill}、也不看冷却，
     * 于是"龙弹冷却中、这轮抽到的是龙息（或什么都没抽到）"时，只要目标进了 44 格，
     * 这个 goal 就会自己转去发射龙弹：冷却被整体绕过（约 1 秒一发）、永远不往 24 格里飞
     * （所以龙息几乎不触发、俯冲的 4~34 格条件也永远不成立、音波更进不了 16 格）。
     * 冷却由 pendingSkill 间接把关 —— pickSkill 只在冷却好了时才会把票投给那个技能。
     */
    private void tickCloseIn(LivingEntity target) {
        double dist = this.dragon.distanceTo(target);
        boolean los = this.dragon.hasLineOfSight(target);
        DragonGolemEntity.DragonSkill skill = this.dragon.pendingSkill();
        // 洞穴里先退到安全距离；没到位之前不切前摇（否则会在敌人脸上站 20+60 tick）
        boolean tooClose = this.dragon.inCave() && !this.dragon.caveAttackDistanceOk(target);
        if (!tooClose && skill == DragonGolemEntity.DragonSkill.BREATH
                && dist <= this.dragon.breathRange() && los) {
            this.orbitSign = this.dragon.getRandom().nextBoolean() ? 1 : -1;
            this.dragon.onSkillStarted(skill);
            this.enter(Phase.BREATH_CHARGE);
            return;
        }
        if (!tooClose && skill == DragonGolemEntity.DragonSkill.ROCKET && dist <= ROCKET_RANGE && los) {
            this.dragon.onSkillStarted(skill);
            this.enter(Phase.ROCKET_AIM);
            return;
        }
        // ★ 没抽中技能（pending=null）但已经打进射程：直接喷息，不要一路飞到 CLOSE_MAX 才停。
        //   start() 里已经有这条分支，但"start 时还没进射程"的那一趟会走到这里，
        //   旧版没有这个分支 → 它飞到站位点后干等 200 tick，看起来就是"不打"。
        if (!tooClose && skill == null && dist <= this.dragon.breathRange() && los) {
            this.orbitSign = this.dragon.getRandom().nextBoolean() ? 1 : -1;
            this.enter(Phase.BREATH_CHARGE);
            return;
        }
        Vec3 aim = new Vec3(target.getX(), this.dragon.getY(), target.getZ());
        this.moveToward(aim, CLOSE_SPEED * this.dragon.flightSpeedFactor());
        if (this.phaseTicks >= CLOSE_MAX) {
            this.finish();
        }
    }

    /**
     * 前摇：<b>真的站住不动</b>、慢慢转头、嘴里聚粒子。
     *
     * <p>原来这里直接调 {@code breathMotion}，也就是一进前摇就已经在绕圈、并且已经压了 20° 弯 ——
     * 前摇和喷息在动作上没有任何区别，只有嘴部粒子在收紧。现在前摇保持悬停，
     * 进 {@link Phase#BREATH} 的那一 tick 才开始绕圈（压弯由模型的 roll 插值平滑过去）。
     */
    private void tickCharge(LivingEntity target) {
        this.aim(target);
        // 前摇期间可能在后撤，但朝向必须继续锁着目标（否则会"倒着飞、背对敌人"）
        this.dragon.setKeepAim(true);
        // ★ 前摇也不是站桩：洞穴里如果目标趁这 20 tick 贴近，就继续往后撤。
        //   旧版 holdHover 是"水平完全不动"，等于把前摇白送给敌人的近战。
        this.caveSpacing(target, CLOSE_SPEED * this.dragon.flightSpeedFactor(), false);
        if (this.phaseTicks == 1) {
            this.dragon.playSound(SoundEvents.ENDER_DRAGON_GROWL, 1.2F, 0.7F);
        }
        this.spawnMouthParticles(this.chargeRadius());
        if (this.phaseTicks >= CHARGE_TICKS) {
            this.executed = DragonGolemEntity.DragonSkill.BREATH;
            this.enter(Phase.BREATH);
            this.dragon.playSound(SoundEvents.ENDER_DRAGON_SHOOT, 1.5F, 0.6F);
        }
    }

    /** 喷息：一道喷流 + 锥形判定（两者共用同一个"被方块挡住的射程"）。 */
    private void tickBreath(LivingEntity target) {
        this.aim(target);
        this.breathMotion(target);
        Vec3 origin = this.dragon.mouthPosition();
        // ★ 洞穴"引导式喷息"：洞里塞不下/穿模都是必然，所以命中不再依赖喷流几何。
        //   只要目标在射程内、同层（Y 差 ≤ cave.guideYTolerance），每 10 tick 直接结算一跳；
        //   粒子仍从嘴里喷（纯表现）。这样即使它卡在台阶/墙里，DPS 也不会归零。
        if (this.dragon.inCave() && DragonGolemConfig.caveGuidedDamage()) {
            // ★ 引导喷息的粒子**直接朝目标身上去**（homing），不再沿"机身朝向"的射线：
            //   洞里空间窄，机身容易被移动抢头、朝向偏开；按射线喷就会看到"糊在墙上"。
            //   伤害本来就是引导判定，粒子也一起锁目标，观感才对得上。
            Vec3 guidedDir = target.getBoundingBox().getCenter().subtract(origin);
            guidedDir = guidedDir.lengthSqr() < 1.0E-4D ? this.breathDirection(origin, target) : guidedDir.normalize();
            double dy = Math.abs(this.dragon.getY() - target.getY());
            // ★ 不许"躲在墙里打"：只有整条龙都待在开阔空间里，引导伤害才结算
            // ★ 判定加"滞回"：bodyClear 每 tick 都会因为微幅环绕而翻转，
            //   恰好翻到 false 的那一 tick 就会漏掉一跳伤害（真机表现：喷到了但伤害不稳定）。
            //   20 tick 内只要有一 tick 是干净的，就允许这段引导伤害成立。
            if (this.dragon.bodyClear()) {
                this.lastClearTick = this.dragon.tickCount;
            }
            boolean clearEnough = this.dragon.tickCount - this.lastClearTick <= 20;
            boolean canHit = clearEnough && this.dragon.bodyClear()
                    && this.dragon.distanceTo(target) <= DragonGolemConfig.caveGuideRange()
                    && dy <= DragonGolemConfig.caveGuideYTolerance();
            this.spawnBreathStream(origin, guidedDir,
                    canHit ? Math.min(this.coneLength(), DragonGolemConfig.caveGuideRange())
                            : this.breathReach(origin, guidedDir));
            if (canHit && this.phaseTicks % 10 == 0
                    && this.dragon.breathDamage(target, BREATH_MULT, BREATH_KNOCKBACK)) {
                this.dragon.applyBreathEffects(target);
            }
            if (this.phaseTicks % 20 == 0) {
                this.dragon.playSound(SoundEvents.ENDER_DRAGON_FLAP, 0.8F, 1.7F);
            }
            if (this.phaseTicks >= BREATH_TICKS) {
                this.enter(Phase.BREATH_RECOVER);
            }
            return;
        }
        Vec3 dir = this.breathDirection(origin, target);
        // 先算一次"喷流能打到多远"，粒子和伤害都用它，见 breathReach 的说明
        double reach = this.breathReach(origin, dir);
        this.spawnBreathStream(origin, dir, reach);
        this.damageCone(origin, dir, reach);
        if (this.phaseTicks % 20 == 0) {
            this.dragon.playSound(SoundEvents.ENDER_DRAGON_FLAP, 0.8F, 1.7F);
        }
        if (this.phaseTicks >= BREATH_TICKS) {
            this.enter(Phase.BREATH_RECOVER);
        }
    }

    /** 龙弹：短前摇瞄准 → 发射 → 收尾。 */
    private void tickRocketAim(LivingEntity target) {
        this.aim(target);
        this.dragon.setKeepAim(true);
        // 洞穴里前摇同样保持站位（别把 10 tick 白送给近战）
        this.caveSpacing(target, CLOSE_SPEED * this.dragon.flightSpeedFactor(), false);
        this.spawnMouthParticles(this.chargeRadius());
        if (this.phaseTicks >= ROCKET_AIM_TICKS) {
            this.fireRocket(target);
            this.enter(Phase.ROCKET_RECOVER);
        }
    }

    private void tickRecover(int length, LivingEntity target) {
        this.caveSpacing(target, CLOSE_SPEED * this.dragon.flightSpeedFactor(), false);
        if (this.phaseTicks >= length) {
            this.finish();
        }
    }

    // ---- 动作 ----

    /** 悬停：水平完全不动，只维持离地高度。 */
    private void holdHover() {
        this.dragon.setDiveVelocity(new Vec3(0.0D, this.hoverDelta(), 0.0D));
    }

    /**
     * 喷息时的移动：要么原地悬停，要么绕圈边飞边喷。
     *
     * <p>绕圈用"目标 → 龙"的水平向量取切线方向，再加一个径向修正（离得太远就往里收、太近就往外推），
     * 所以不会越绕越远、也不会一路顶到目标脸上。朝向另交给 {@code aimAt}（本 tick 关掉 faceMovement），
     * 这样身子一直对着目标、龙息不会跟着切线甩开。
     *
     * <p>半径本身是 {@link #orbitRadius()}：它是从<b>身体中心</b>量的，而龙头在身体前方
     * （1 倍 7.2 格、泰坦 4 倍 29 格），所以半径必须比"嘴的前伸量"大，
     * 否则龙头会直接穿到目标身上甚至背后去。
     */
    private void breathMotion(LivingEntity target) {
        if (!BREATH_WHILE_FLYING) {
            this.holdHover();
            return;
        }
        // ★ 龙息现在是<b>攻击动画</b>（{@link DragonBreathAnimation}），不是行动逻辑：
        //   动画自己给出"此刻该在圆弧的哪一点、压多少弯"；
        //   平原 = 围绕目标转圈，洞穴 = 原地站桩（同一个动画类里按 inCave 分支）。
        //   朝向仍然由本 tick 先跑的 aim(target) 负责（动画不再写 yaw，避免一 tick 两次）。
        this.dragon.setKeepAim(true);
        this.breathAnim.tick(this.dragon, target, this.hoverDelta());
        // 动画只负责"想去的速度"；熔岩规避/撞墙避障由飞行辅助再叠一层。
        // （幽灵模式下 avoidance 只做熔岩规避，不会把穿墙直线改开。）
        Vec3 animated = this.breathAnim.lastVelocity();
        Vec3 adjusted = this.dragon.flightAssist().avoidance(this.dragon, animated);
        // 幽灵穿墙时如果身体还压在方块里，喷流会被墙截断；边喷边往最近的开放方向挪一点。
        if (this.dragon.isGhostFlight() && !this.dragon.bodyClear()) {
            // 贴着天花板时禁止向上找出口，否则会被"最近的开放方向 = 正上"顶进石头里
            double vyIntent = this.hoverDelta();
            boolean allowUp = vyIntent > 0.0D;
            Vec3 open = this.dragon.flightAssist().openDirection(this.dragon, allowUp);
            if (open != null) {
                double vy = Math.max(adjusted.y, open.y * 0.6D);
                if (!allowUp) {
                    vy = Math.min(vy, 0.0D);
                }
                adjusted = new Vec3(open.x * 0.6D + adjusted.x * 0.4D, vy,
                        open.z * 0.6D + adjusted.z * 0.4D);
            }
        }
        this.dragon.setDiveVelocity(adjusted);
    }

    /** 上一次"整条龙待在开阔空间"的 tick（引导喷息判定的滞回用）。 */
    private int lastClearTick = Integer.MIN_VALUE / 2;

    /** 平原龙息的转圈动画（每个 goal 一份）。 */
    private final DragonBreathAnimation breathAnim = new DragonBreathAnimation();

    /** 这一 tick 的锥体长度（格）：1 倍基准 × 体型倍率。 */
    private double coneLength() {
        return CONE_LENGTH * this.dragon.combatGeoScale();
    }

    /**
     * 朝一个水平点飞（保持悬停高度），转向交给实体的 faceMovement。
     *
     * <p>洞穴里它同时是<b>站位控制器</b>：太近就后退、到站位带就停、太远才靠近。
     * 旧版只做"到了就停"，目标自己走过来时龙不会退 —— 那就是"贴上去挨揍"的另一半。
     */
    private void moveToward(Vec3 point, double speed) {
        LivingEntity target = this.dragon.dragonCurrentTarget();
        if (this.dragon.inCave() && target != null) {
            double dist = this.dragon.distanceTo(target);
            double standoff = this.dragon.caveStandoff(target);
            // ★ 看不见目标时别在站位距离外干等：喷息打不穿墙，站在远处只会
            //   "有距离、没输出"。先贴近到"近战仍然够不到"的距离去拿视线，
            //   拿到视线后再由下面的站位带把距离拉开。
            if (!this.dragon.hasLineOfSight(target)) {
                // 幽灵能穿墙：直接穿到目标那一侧去拿视线，拿到之后再按站位带退开。
                // 非幽灵则只贴近到"近战仍然够不到"的距离，避免为了视线白挨打。
                standoff = this.dragon.isGhostFlight()
                        ? Math.min(standoff, 2.5D)
                        : Math.min(standoff, Math.max(4.0D, this.dragon.meleeSafeDistance(target)));
            }
            // ★ 已经在近战范围里：主动后撤，而不是站着等它把距离磨到 0
            if (dist < standoff - 0.5D) {
                this.retreatFrom(target, speed);
                return;
            }
            // ★ 站位带：别再往前顶（真机 dist=4.3 被 mob 打中，就是因为这里没有"停"）
            if (dist <= standoff + 0.75D) {
                if (!this.nudgeIntoOpenSpace(speed * 0.8D)) {
                    this.holdHover();
                }
                return;
            }
        }
        boolean ghost = this.dragon.isGhostFlight();
        // ★ 幽灵模式：直线就是合法路线，不查 A*、不因为"没路线"站桩、撞墙也不作废（本来就能穿）
        if (!ghost && this.dragon.horizontalCollision) {
            this.dragon.pathFollower().invalidate();
        }
        if (!ghost && this.dragon.pathFollower().noRoute()) {
            // ★ 没路线就停在那里；但仍每 tick 调一次 steer()，让跟随器在
            //   nextPathTry 到点时（每秒最多一次）后台重算。算出来下一 tick 自动恢复移动。
            this.dragon.pathFollower().steer(point, 1);
            this.holdHover();
            if (DragonGolemConfig.pathLog() && this.dragon.tickCount % 20 == 0) {
                Dragon_golems.LOGGER.info("[path] noRoute → hold pos=({},{}) failTicks={} nextTry={}",
                        String.format("%.1f", this.dragon.getX()),
                        String.format("%.1f", this.dragon.getZ()),
                        this.dragon.pathFollower().failTicks(),
                        this.dragon.pathFollower().nextPathTry());
            }
            return;
        }
        Vec3 goal = ghost ? point : this.dragon.pathFollower().steer(point, 1);
        // 航线诊断（默认开）：目标 / 当前航点 / 是否在航线上 / 是否无路 / 节点数 / 是否贴墙
        if (DragonGolemConfig.pathLog() && this.dragon.tickCount % 20 == 0) {
            Dragon_golems.LOGGER.info(
                    "[path] goal=({},{}) wp=({},{},{}) ghost={} onRoute={} noRoute={} nodes={} smoothed={} hColl={}",
                    String.format("%.1f", point.x), String.format("%.1f", point.z),
                    String.format("%.1f", goal.x), String.format("%.1f", goal.y), String.format("%.1f", goal.z),
                    ghost, this.dragon.pathFollower().onRoute(), this.dragon.pathFollower().noRoute(),
                    this.dragon.pathFollower().pathNodeCount(), this.dragon.pathFollower().smoothedNodes(),
                    this.dragon.horizontalCollision);
        }
        double dx = goal.x - this.dragon.getX();
        double dz = goal.z - this.dragon.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        Vec3 velocity;
        if (len < 0.05D) {
            velocity = Vec3.ZERO;
        } else {
            velocity = new Vec3(dx / len * speed, 0.0D, dz / len * speed);
        }
        // ★ 扇面避障（待机 goal 一直在用，攻击 goal 之前没有 → 这就是"贴柱子蹭"的直接原因）
        Vec3 want = new Vec3(velocity.x, this.hoverDelta(), velocity.z);
        this.dragon.setDiveVelocity(this.dragon.flightAssist().avoidance(this.dragon, want));
    }

    /**
     * 洞穴里保持站位：前摇/喷息收尾时调用。
     *
     * @param allowApproach 允许靠近时才走 {@link #moveToward}；前摇/收尾只允许停或退
     */
    private void caveSpacing(LivingEntity target, double speed, boolean allowApproach) {
        if (!this.dragon.inCave()) {
            this.holdHover();
            return;
        }
        double dist = this.dragon.distanceTo(target);
        double standoff = this.dragon.caveStandoff(target);
        if (dist < standoff - 0.5D) {
            this.retreatFrom(target, speed);
            return;
        }
        if (allowApproach && dist > standoff + 0.75D) {
            this.moveToward(new Vec3(target.getX(), this.dragon.getY(), target.getZ()), speed);
            return;
        }
        if (!this.nudgeIntoOpenSpace(speed * 0.8D)) {
            this.holdHover();
        }
    }

    /**
     * 幽灵模式专用的"从墙里出来"：站位带已经到了，但身体还压在方块里时，
     * 往最近的开放方向挪一点再停下。否则会出现"站位正确、但整条龙在墙里喷墙"。
     *
     * @return 是否已经接管本 tick 的速度
     */
    private boolean nudgeIntoOpenSpace(double speed) {
        if (!this.dragon.inCave() || !this.dragon.isGhostFlight() || this.dragon.bodyClear()) {
            return false;
        }
        // 贴着天花板时只找水平/向下出口：不能再用"正上"把它推出洞顶
        double vyIntent = this.hoverDelta();
        boolean allowUp = vyIntent > 0.0D;
        Vec3 open = this.dragon.flightAssist().openDirection(this.dragon, allowUp);
        if (open == null) {
            return false;
        }
        // open 是三维单位向量；水平部分照走，竖直部分保留悬停意图但禁止向上时压到 0 以下
        double vy = Math.max(vyIntent, open.y > 0.0D ? 0.12D : 0.0D);
        if (!allowUp) {
            vy = Math.min(vy, 0.0D);
        }
        Vec3 want = new Vec3(open.x * speed, vy, open.z * speed);
        this.dragon.setDiveVelocity(this.dragon.flightAssist().avoidance(this.dragon, want));
        return true;
    }

    /**
     * 后退：远离目标，优先走"整条龙塞得下且不沾液体"的方向（幽灵模式允许直接穿墙）。
     *
     * <p>竖直方向保留 {@link #hoverDelta()} 的意图：能爬高时顺便拉开高度，
     * 洞穴里爬不动就纯水平退。
     */
    private void retreatFrom(LivingEntity target, double speed) {
        Vec3 away = new Vec3(this.dragon.getX() - target.getX(), 0.0D,
                this.dragon.getZ() - target.getZ());
        if (away.horizontalDistance() < 1.0E-4D) {
            Vec3 look = this.dragon.getLookAngle();
            away = new Vec3(-look.x, 0.0D, -look.z);
            if (away.horizontalDistance() < 1.0E-4D) {
                away = new Vec3(1.0D, 0.0D, 0.0D);
            }
        }
        Vec3 dir = this.dragon.flightAssist().retreatDirection(this.dragon, away);
        Vec3 want = new Vec3(dir.x * speed, this.hoverDelta(), dir.z * speed);
        this.dragon.setDiveVelocity(this.dragon.flightAssist().avoidance(this.dragon, want));
    }

    /** 维持离地高度的竖直速度（和待机/俯冲拉升同一套写法）。上限跟着速度倍率放大，否则高速时爬不动。 */
    private double hoverDelta() {
        // ★ 竖直优先级链：战斗高度 / 头顶上限 / 航线高度（取最小，见 verticalTargetY）
        double wantY = this.dragon.combatAltitudeY();
        // ★ 不要再额外压到"目标脚底 + 3"：那会把 combatAltitudeY 算好的
        //   "目标头顶 + cave.attackHeight"直接抹掉，龙就贴着目标脑袋飞（近战正好够到）。
        //   真正的上界在 combatAltitudeY 里已经按天花板 + cave.ceilingSlack 夹过了。
        double cap = 0.18D * this.dragon.speedScale();
        double vy = Mth.clamp((wantY - this.dragon.getY()) * 0.10D, -cap, cap);
        // ★ 洞穴模式：允许"小台阶爬升"，但不许一路飞向天花板。
        //   （上一版这里是"非航线就完全禁止上升"——结果被楼梯/平台挡住时永远抬不起来，
        //     卡住 → stuck 累积 → 常驻幽灵 → 期间又不攻击，就是真机看到的"莫名其妙不打"。）
        if (this.dragon.inCave() && !this.dragon.pathFollower().onRoute()) {
            double headroom = this.dragon.ceilingFeetLimit() - this.dragon.getY();
            // 被挡住时允许抬 3 格（翻台阶/平台），平时只留 1 格的微调余量
            double stepUp = this.dragon.horizontalCollision ? 3.0D : 1.0D;
            double allow = Math.max(0.0D, Math.min(stepUp, headroom - 0.3D));
            wantY = Math.min(wantY, this.dragon.getY() + allow);
        }
        // ★ 天花板前瞻保护：脚底不许越过本列（及前后列）的天花板上限
        double limit = this.dragon.noPhysics ? Double.MAX_VALUE : this.dragon.ceilingFeetLimit();
        if (this.dragon.getY() + vy > limit) {
            vy = Mth.clamp(limit - this.dragon.getY(), -cap, cap);
        }
        return vy;
    }

    /**
     * 把身子转向目标（和 faceMovement 共用 yRot/yBodyRot 的同步规则）。
     *
     * <p>转速必须跟着"绕圈所需的角速度"一起涨：绕圈的角速度 = 切向速度 / 半径。
     * 幽匿 + 泰坦那种 1.65 格/tick 绕 16 格半径 ≈ 5.9°/tick，而原来写死 {@link #AIM_TURN}(6°/tick)
     * 只是刚刚够 —— 速度再高一点、或者被径向修正把半径拉近一点，模型就转不过来、一直斜着身子飞。
     * 这里直接复用实体那套"按速度放大转向"的公式，模型和判定箱读的都是同一个 yaw。
     */
    private void aim(LivingEntity target) {
        double horizontal = ORBIT_SPEED * this.dragon.flightSpeedFactor();
        float rate = Math.max(AIM_TURN, this.dragon.turnRateFor(horizontal));
        this.dragon.aimAt(target.getX(), target.getZ(), rate);
    }

    /** 从嘴部指向目标胸口；目标太近就用当前朝向兜底。 */
    private Vec3 breathDirection(Vec3 origin, LivingEntity target) {
        Vec3 to = target.getBoundingBox().getCenter().subtract(origin);
        return to.lengthSqr() < 1.0E-6D ? this.dragon.getLookAngle() : to.normalize();
    }

    private void fireRocket(LivingEntity target) {
        this.executed = DragonGolemEntity.DragonSkill.ROCKET;
        Vec3 origin = this.dragon.mouthPosition();
        Vec3 dir = target.getBoundingBox().getCenter().subtract(origin);
        if (dir.lengthSqr() < 1.0E-6D) {
            dir = this.dragon.getLookAngle();
        }
        dir = dir.normalize();
        dir = new Vec3(
                dir.x + (this.dragon.getRandom().nextDouble() - 0.5D) * ROCKET_SPREAD,
                dir.y + (this.dragon.getRandom().nextDouble() - 0.5D) * ROCKET_SPREAD,
                dir.z + (this.dragon.getRandom().nextDouble() - 0.5D) * ROCKET_SPREAD);
        DragonGolemFireball ball = new DragonGolemFireball(this.dragon.level(), this.dragon, origin, dir);
        this.dragon.level().addFreshEntity(ball);
        this.dragon.playSound(SoundEvents.ENDER_DRAGON_SHOOT, 1.6F, 0.8F);
        if (this.dragon.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.EXPLOSION, origin.x, origin.y, origin.z, 2,
                    0.2D, 0.2D, 0.2D, 0.0D);
        }
    }

    // ---- 锥形判定 ----

    /**
     * 从嘴沿瞄准轴打一条射线，返回"喷流实际能打到多远"（被方块挡住就到此为止，没挡住就是 {@link #CONE_LENGTH}）。
     *
     * <p><b>伤害和粒子共用这一个值</b>，专门修"喷流明明停在墙前面、墙后面的怪却在掉血"：
     * 原来 {@code damageCone} 只做几何判定、从不看方块，隔墙隔地照样结算，而粒子流会 clip 停住，
     * 两边的有效距离根本对不上。这里每 tick 只算一次（不是每个目标算一次），
     * 代价和"画粒子前那一次 clip"完全相同。
     *
     * <p>已知的近似：射线只走轴线，所以"斜着绕过墙角"的目标仍可能被判进来。锥体半角只有
     * {@link #CONE_HALF_ANGLE}(16°)，这点偏差可以接受；真要严格就得对每个候选目标各打一条射线。
     */
    private double breathReach(Vec3 origin, Vec3 dir) {
        double cone = this.coneLength();
        Vec3 end = origin.add(dir.scale(cone));
        BlockHitResult hit = this.dragon.level().clip(new ClipContext(origin, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.dragon));
        return hit.getType() == HitResult.Type.MISS ? cone : origin.distanceTo(hit.getLocation());
    }

    /**
     * 从嘴到目标胸口有没有被方块挡住（每个候选目标单独查）。
     *
     * <p>终点停在目标碰撞箱表面之前，避免"射线打中目标自己脚下的方块"。
     * 目标实体本身不参与方块碰撞，不需要把实体算进去。
     */
    private boolean hasBreathLine(Vec3 origin, LivingEntity victim) {
        Vec3 to = victim.getBoundingBox().getCenter().subtract(origin);
        double dist = to.length();
        if (dist < 1.0E-4D) {
            return true;
        }
        double surface = Math.max(0.0D, dist - victim.getBbWidth() * 0.5D);
        if (surface <= 0.0D) {
            return true;
        }
        Vec3 end = origin.add(to.scale(surface / dist));
        BlockHitResult hit = this.dragon.level().clip(new ClipContext(
                origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.dragon));
        return hit.getType() == HitResult.Type.MISS;
    }

    /**
     * 锥体伤害：先拿一个粗包围盒筛候选，再逐个做"沿轴距离 + 侧向偏移"判定。
     * 侧向阈值带上目标半宽（{@code +0.5} 的宽容度），所以小目标也不容易漏。
     *
     * <p>轴向的截止距离用 {@code reach}（{@link #breathReach} 算出来的、被方块挡住的实际射程），
     * 不是写死的锥体长度 —— 墙后面的目标不该被喷到。
     *
     * @param reach 喷流实际能打到的距离（格），由 {@link #breathReach} 给出
     */
    private void damageCone(Vec3 origin, Vec3 dir, double reach) {
        // 粗筛盒只到"实际打到的距离 + 2"，不要用满锥体长度：
        // 泰坦体型的锥体有 120 格，按满长做实体查询会白扫一大片空区。
        Vec3 end = origin.add(dir.scale(Math.min(this.coneLength(), reach + 2.0D)));
        AABB box = new AABB(origin, end).inflate(2.0D);
        for (LivingEntity victim : this.dragon.level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (victim == this.dragon || !victim.isAlive() || victim.isSpectator()) {
                continue;
            }
            if (this.dragon.hasPassenger(victim) || victim.isPassengerOfSameVehicle(this.dragon)) {
                continue;
            }
            if (!this.dragon.predicateTarget(victim) || !this.dragon.canAttack(victim)) {
                continue;
            }
            // ★ 每个候选目标单独做一次"嘴 → 目标"的方块遮挡检查。
            //   只检查轴线的话，锥体里"上方/后方但斜着绕过墙角"的目标会被隔着地板喷到；
            //   真机就是"锁下面的、上面那个掉血"。
            if (!this.hasBreathLine(origin, victim)) {
                continue;
            }
            Vec3 to = victim.getBoundingBox().getCenter().subtract(origin);
            double along = to.dot(dir);
            // 轴向截止 = 喷流真正打到的距离 + 目标自己的半宽（紧贴墙站的目标应该还能被喷到）
            if (along < 0.0D || along > reach + victim.getBbWidth() * 0.5D) {
                continue;
            }
            double lateral = Math.sqrt(Math.max(0.0D, to.lengthSqr() - along * along));
            double limit = Math.tan(Math.toRadians(CONE_HALF_ANGLE)) * along
                    + victim.getBbWidth() * 0.5D + 0.5D;
            if (lateral > limit) {
                continue;
            }
            int id = victim.getId();
            Integer next = this.nextHit.get(id);
            if (next != null && this.dragon.tickCount < next) {
                continue;
            }
            this.nextHit.put(id, this.dragon.tickCount + HIT_COOLDOWN);
            if (this.dragon.breathDamage(victim, BREATH_MULT, BREATH_KNOCKBACK)) {
                this.dragon.applyBreathEffects(victim);
            }
        }
    }

    // ---- 表现 ----

    /** 嘴里聚拢的粒子：越接近喷出越收紧。 */
    private void spawnMouthParticles(double radius) {
        if (!(this.dragon.level() instanceof ServerLevel level)) {
            return;
        }
        Vec3 mouth = this.dragon.mouthPosition();
        level.sendParticles(this.dragon.breathParticle(), mouth.x, mouth.y, mouth.z, 2,
                radius, radius, radius, 0.0D);
    }

    /**
     * 喷流表现：<b>一条从嘴射向落点的密集粒子流</b>，落点再炸开一小团。
     *
     * <p>之前是"按锥体体积随机撒点"，看起来像一层雾罩在锥形范围里，没有方向感；
     * 现在是"射线 + 轻微扩散 + 落点溅射"：沿着瞄准轴把绝大部分粒子铺在"嘴 → 落点"这一段上
     * （越靠后越散一点点），最后在落点补一团。
     *
     * @param reach 喷流实际能打到的距离（格）：由 {@link #breathReach} 预先算好、和伤害共用，
     *              所以这里不再自己打射线，粒子和伤害不会各停在各的地方
     */
    private void spawnBreathStream(Vec3 origin, Vec3 dir, double reach) {
        if (!(this.dragon.level() instanceof ServerLevel level)) {
            return;
        }

        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right = dir.cross(up);
        if (right.lengthSqr() < 1.0E-4D) {
            right = new Vec3(1.0D, 0.0D, 0.0D);
        }
        right = right.normalize();
        Vec3 realUp = right.cross(dir).normalize();

        // 主线：整条线都关在"嘴 → 落点"这一段里（最多 CONE_LENGTH 格），
        // 越靠后散得越开，但始终是一股，不是一团罩在锥形范围里的雾。
        for (int i = 0; i < STREAM_SAMPLES; i++) {
            double t = this.dragon.getRandom().nextDouble();
            double spread = (0.10D + 0.30D * t) * (1.0D + t);
            double a = this.dragon.getRandom().nextDouble() * Math.PI * 2.0D;
            double r = spread * Math.sqrt(this.dragon.getRandom().nextDouble());
            Vec3 at = origin.add(dir.scale(t * reach))
                    .add(right.scale(Math.cos(a) * r))
                    .add(realUp.scale(Math.sin(a) * r));
            level.sendParticles(this.dragon.breathParticle(), at.x, at.y, at.z, 2,
                    0.0D, 0.0D, 0.0D, 0.0D);
        }

        // 落点溅射：打在地/墙上时才有，看起来像"舔到东西了"（reach == 满锥长 = 没打到东西）
        if (reach < this.coneLength() - 0.5D) {
            Vec3 at = origin.add(dir.scale(reach));
            level.sendParticles(this.dragon.breathParticle(), at.x, at.y + 0.15D, at.z, 8,
                    0.5D, 0.25D, 0.5D, 0.02D);
            // 烟雾和岩浆火星是"火"的语义：幽匿龙喷的是音波，别在落点炸出火来
            if (!this.dragon.isSonicBody()) {
                level.sendParticles(ParticleTypes.SMOKE, at.x, at.y + 0.1D, at.z, 3,
                        0.35D, 0.2D, 0.35D, 0.01D);
                level.sendParticles(ParticleTypes.LAVA, at.x, at.y + 0.1D, at.z, 1,
                        0.2D, 0.1D, 0.2D, 0.0D);
            }
        }
        // 嘴口的余焰，让"从嘴里喷出来"这件事更清楚
        level.sendParticles(this.dragon.breathParticle(), origin.x, origin.y, origin.z, 4,
                0.12D, 0.12D, 0.12D, 0.0D);
    }

    /** 前摇粒子的聚拢半径：越接近喷出越收紧。 */
    private double chargeRadius() {
        double t = Mth.clamp(this.phaseTicks / (double) CHARGE_TICKS, 0.0D, 1.0D);
        return 0.9D - 0.7D * t;
    }

    // ---- 杂项 ----

    private void finish() {
        this.finished = true;
        this.stop();
    }

    private void enter(Phase next) {
        this.phase = next;
        this.phaseTicks = 0;
        // 前摇一开始就开一轮龙息：递减计数必须在"第一跳落地之前"清空（见 DragonGolemEntity.beginBreathVolley）
        if (next == Phase.BREATH_CHARGE) {
            this.dragon.beginBreathVolley();
            // 动画每轮重新起相（从当前方位接着转，不跳相位）
            this.breathAnim.end();
        }
        // 只有开火阶段才上报姿态；靠拢阶段保持水平（否则会一直低着头飞）
        this.dragon.setDivePhase(next == Phase.CLOSE_IN
                ? DragonGolemEntity.DIVE_PHASE_NONE
                : DragonGolemEntity.DIVE_PHASE_BREATH);
        if (DEBUG) {
            Dragon_golems.LOGGER.info("[ranged] phase={} target={}", next,
                    this.dragon.dragonCurrentTarget() == null ? "null" : this.dragon.dragonCurrentTarget().getName().getString());
        }
    }
}

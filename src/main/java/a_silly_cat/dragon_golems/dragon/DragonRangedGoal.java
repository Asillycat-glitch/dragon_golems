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
        if (this.dragon.getControllingPassenger() instanceof Player) {
            return false;
        }
        LivingEntity target = this.dragon.getTarget();
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
        // 没被抽中时只负责"靠拢"：目标在喷息距离外、或者没视线时飞过去。
        // 这一段是纯走位，不吃出手间隔，所以不会出现"打不着就干等"。
        return !(this.dragon.distanceTo(target) <= this.dragon.breathRange()
                && this.dragon.hasClearShotTo(target));
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
        LivingEntity target = this.dragon.getTarget();
        if (target == null) {
            this.finished = true;
            return;
        }
        DragonGolemEntity.DragonSkill skill = this.dragon.pendingSkill();
        double dist = this.dragon.distanceTo(target);
        boolean los = this.dragon.hasClearShotTo(target);
        // 连续空轮（上一轮喷了但一个人都没打到）→ 换几何：站桩 + 距离压到 75%
        this.hoverOnly = this.dragon.breathMissStreak() >= 2;
        double gate = this.hoverOnly ? this.dragon.breathRange() * RETRY_RANGE_FACTOR : this.dragon.breathRange();
        if (skill == DragonGolemEntity.DragonSkill.BREATH
                && this.muzzleInGate(target, gate) && los) {
            // 每次开喷随机挑一个绕行方向（顺时针/逆时针），免得永远往同一边转
            this.orbitSign = this.dragon.getRandom().nextBoolean() ? 1 : -1;
            this.dragon.onSkillStarted(skill);
            this.enter(Phase.BREATH_CHARGE);
        } else if (skill == DragonGolemEntity.DragonSkill.ROCKET
                && this.muzzleInGate(target, ROCKET_RANGE) && los) {
            this.dragon.onSkillStarted(skill);
            this.enter(Phase.ROCKET_AIM);
        } else {
            // 没抽中、或条件不满足 → 这一趟只负责飞过去（太近就先后退）
            this.enter(Phase.CLOSE_IN);
        }
        this.dragon.setAggressive(true);
    }

    @Override
    public void stop() {
        this.dragon.setDiveVelocity(null);
        this.dragon.setDivePhase(DragonGolemEntity.DIVE_PHASE_NONE);
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
        LivingEntity target = this.dragon.getTarget();
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
            this.tickRecover(RECOVER_TICKS);
        } else if (this.phase == Phase.ROCKET_AIM) {
            this.tickRocketAim(target);
        } else {
            this.tickRecover(ROCKET_RECOVER_TICKS);
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
        boolean los = this.dragon.hasClearShotTo(target);
        DragonGolemEntity.DragonSkill skill = this.dragon.pendingSkill();
        double gate = this.hoverOnly ? this.dragon.breathRange() * RETRY_RANGE_FACTOR : this.dragon.breathRange();
        if (skill == DragonGolemEntity.DragonSkill.BREATH
                && this.muzzleInGate(target, gate) && los) {
            this.orbitSign = this.dragon.getRandom().nextBoolean() ? 1 : -1;
            this.dragon.onSkillStarted(skill);
            this.enter(Phase.BREATH_CHARGE);
            return;
        }
        if (skill == DragonGolemEntity.DragonSkill.ROCKET
                && this.muzzleInGate(target, ROCKET_RANGE) && los) {
            this.dragon.onSkillStarted(skill);
            this.enter(Phase.ROCKET_AIM);
            return;
        }
        // ★ 嘴插进目标里（喷口已经越过目标）→ 先后退到"嘴和目标有间隙"的位置，
        //   否则锥体的 along 全为负、怎么喷都是零伤害（真机：miss=3 站桩喷）。
        //   ★ 带滞回：进入后退门槛是 MIN_MUZZLE_GAP，退到 MIN + 4×体型才允许重新靠近 ——
        //   否则会在边界上"退一下、靠一下"地来回摆（用户实测的"前后移动"）。
        double mouthGapMin = MIN_MUZZLE_GAP * this.dragon.combatGeoScale();
        double mouthGapExit = mouthGapMin + MUZZLE_GAP_HYSTERESIS * this.dragon.combatGeoScale();
        double mouthDist = this.dragon.mouthDistanceTo(target);
        if (mouthDist < mouthGapMin) {
            this.backingOff = true;
        } else if (mouthDist > mouthGapExit) {
            this.backingOff = false;
        }
        if (this.backingOff) {
            Vec3 away = this.dragon.position().subtract(target.position());
            double len = Math.sqrt(away.x * away.x + away.z * away.z);
            if (len < 1.0E-3D) {
                away = new Vec3(1.0D, 0.0D, 0.0D);
                len = 1.0D;
            }
            double want = mouthGapExit + this.dragon.mouthForward() + 2.0D;
            Vec3 back = new Vec3(target.getX() + away.x / len * want, this.dragon.getY(),
                    target.getZ() + away.z / len * want);
            this.moveToward(back, CLOSE_SPEED * this.dragon.flightSpeedFactor());
        } else {
            Vec3 aim = new Vec3(target.getX(), this.dragon.getY(), target.getZ());
            this.moveToward(aim, CLOSE_SPEED * this.dragon.flightSpeedFactor());
        }
        if (this.phaseTicks >= CLOSE_MAX) {
            this.finish();
        }
    }

    /**
     * 射程闸门用<b>喷口（头部子碰撞箱）到目标</b>的距离，而不是身体中心距离。
     *
     * <p>见 {@link DragonGolemEntity#mouthDistanceTo}：体型越大喷口越靠前（泰坦 30+ 格），
     * 用身体中心距离会在"看着很近"的时候让嘴越过目标，锥体打空。
     */
    private boolean muzzleInGate(LivingEntity target, double maxRange) {
        double mzd = this.dragon.mouthDistanceTo(target);
        return mzd >= MIN_MUZZLE_GAP * this.dragon.combatGeoScale() && mzd <= maxRange;
    }

    /** 喷口与目标之间至少留多少格（按体型放大），低于它就先后退再开火。 */
    private static final double MIN_MUZZLE_GAP = 2.5D;
    /** 后退的滞回宽度（按体型放大）：退到 MIN + 这个值之后才允许重新靠近，避免边界上前后摆。 */
    private static final double MUZZLE_GAP_HYSTERESIS = 4.0D;
    /** 当前是否处于"后退拉开喷口间隙"的状态（见上）。 */
    private boolean backingOff;

    /**
     * 前摇：<b>真的站住不动</b>、慢慢转头、嘴里聚粒子。
     *
     * <p>原来这里直接调 {@code breathMotion}，也就是一进前摇就已经在绕圈、并且已经压了 20° 弯 ——
     * 前摇和喷息在动作上没有任何区别，只有嘴部粒子在收紧。现在前摇保持悬停，
     * 进 {@link Phase#BREATH} 的那一 tick 才开始绕圈（压弯由模型的 roll 插值平滑过去）。
     */
    private void tickCharge(LivingEntity target) {
        this.aim(target);
        this.holdHover();
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
        // ★ 视线丢了（墙/柱挡住了）：这一轮喷不到人，别原地烧完 60 tick ——
        //   提前收招，交给 CLOSE_IN 去换位置（洞窟模式下"低移动欲望"不等于"对着墙喷"）。
        if (this.dragon.hasClearShotTo(target)) {
            this.noLosTicks = 0;
        } else if (++this.noLosTicks == LOS_LOST_MAX + 1) {
            // 只在"刚好越线"那一刻打一行（以前是每 tick 打一行，日志被刷满）
            if (DragonDebug.RIDE) {
                Dragon_golems.LOGGER.info("[breath] 视线被挡 {} tick → 提前收招换位置", this.noLosTicks);
            }
        }
        if (this.noLosTicks > LOS_LOST_MAX) {
            this.enter(Phase.BREATH_RECOVER);
            return;
        }
        Vec3 origin = this.dragon.mouthPosition();
        Vec3 dir = this.breathDirection(origin, target);
        // 先算一次"喷流能打到多远"，粒子和伤害都用它，见 breathReach 的说明
        double reach = this.breathReach(origin, dir);
        this.spawnBreathStream(origin, dir, reach);
        this.damageCone(origin, dir, reach);
        if (this.phaseTicks % 20 == 0) {
            this.dragon.playSound(SoundEvents.ENDER_DRAGON_FLAP, 0.8F, 1.7F);
        }
        // 诊断（限频：每 10 tick 一行，一次喷息最多 6 行）：
        // 排查"喷息抽搐"时看哪个量在抖 —— yRot/yBody 抖=朝向，dy 抖=竖直，dvx/dvz 抖=绕圈，roll=压弯。
        if (DragonDebug.RIDE && this.phaseTicks % 10 == 0) {
            Vec3 v = this.dragon.getDeltaMovement();
            Dragon_golems.LOGGER.info(
                    "[breath] t={} yRot={} body={} roll={} dy={} dvx={} dvz={} len={} sign={} thr={} blk={} miss={} ovr={} mzd={} R={} arc={} dhits={} aimErr={}",
                    this.phaseTicks,
                    String.format("%.1f", this.dragon.getYRot()),
                    String.format("%.1f", this.dragon.yBodyRot),
                    String.format("%.1f", this.dragon.getBodyRoll()),
                    String.format("%.3f", v.y),
                    String.format("%.3f", v.x), String.format("%.3f", v.z),
                    String.format("%.1f", Math.hypot(this.dragon.getX() - target.getX(),
                            this.dragon.getZ() - target.getZ())),
                    this.orbitSign,
                    String.format("%.2f", this.dragon.flightController().throttle()),
                    this.dragon.flightIntentBlockedTicks(),
                    this.dragon.breathMissStreak(), this.hoverOnly,
                    String.format("%.1f", this.dragon.mouthDistanceTo(target)),
                    String.format("%.1f", this.arcRadius),
                    String.format("%.0f", Math.toDegrees(this.arcPhase)),
                    this.dragon.breathHitsThisVolley(),
                    String.format("%.1f", Mth.wrapDegrees(
                            (float) (Mth.atan2(target.getZ() - this.dragon.getZ(),
                                    target.getX() - this.dragon.getX()) * 180.0D / Math.PI) - 90.0F
                                    - this.dragon.getYRot())));
        }
        if (this.phaseTicks >= BREATH_TICKS) {
            this.enter(Phase.BREATH_RECOVER);
        }
    }

    /** 龙弹：短前摇瞄准 → 发射 → 收尾。 */
    private void tickRocketAim(LivingEntity target) {
        this.aim(target);
        this.holdHover();
        this.spawnMouthParticles(this.chargeRadius());
        if (this.phaseTicks >= ROCKET_AIM_TICKS) {
            this.fireRocket(target);
            this.enter(Phase.ROCKET_RECOVER);
        }
    }

    private void tickRecover(int length) {
        this.holdHover();
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
    /**
     * 喷息时的移动：绕着目标画弧（边飞边喷）。
     *
     * <h2>★ 不在这里判断撞墙</h2>
     * 第一版在这里加了"撞墙就停止绕圈、原地悬停"，结果真机**抽搐**：
     * {@code horizontalCollision} 是会闪的（擦一下地形就是一拍 true），
     * 于是速度在"切向 0.35"和"0"之间跳、压弯在 12° 和 0° 之间跳 —— 一开一关就是抽搐。
     *
     * <p>现在这件事交给执行层（{@link DragonFlightController#applyIntent}）：
     * 它按<b>持续</b>碰撞 tick 数决定，并且是<b>平滑降速</b>（throttle 低通），
     * 所以瞬时擦碰只会让速度轻轻收一下，不会跳变。
     */
    private void breathMotion(LivingEntity target) {
        if (!BREATH_WHILE_FLYING) {
            this.holdHover();
            return;
        }
        // 连续空轮 → 站桩喷（换几何，见 hoverOnly 的说明）
        if (this.hoverOnly) {
            this.aim(target);
            this.dragon.setBodyRollDirect(0.0F);
            this.holdHover();
            return;
        }
        double speed = ORBIT_SPEED * this.dragon.flightSpeedFactor();
        // ★ 空间预算：洞里不旋转（原地悬停扫射），平地才绕圈（观感优先）。
        DragonGolemEntity.SpaceBudget space = this.dragon.spaceBudget();
        // 弧线半径 = 绕圈半径 × 1.3：orbitRadius 是"嘴刚好够得到目标"的下限，
        // 按它绕圈时喷口离目标只有 3 格（真机 mzd=3.2）—— 看起来像"鼻子顶在目标脸上喷"。
        // 放大 1.3 倍后喷口到目标约 6~8 格，锥体覆盖正常、画面也像在扫射而不是贴脸。
        double radius = this.dragon.orbitRadius() * ARC_RADIUS_FACTOR;
        // 净空连这个半径都给不了 → 绕圈没意义，站桩喷
        if (!space.roomy() || space.clearance() < radius * 0.8D) {
            this.dragon.setKeepAim(true);
            this.dragon.setBodyRollDirect(0.0F);
            this.holdHover();
            return;
        }
        // ★ 绕圈 = 脚本弧线（相位驱动的 waypoint）+ 阻尼径向修正。
        //   纯"瞄准 speed 前方那一点"的追踪在半径误差上几乎没有切向引导：
        //   真机 len 在 9.7~12.7 之间摆（R=11），mzd 3.2~7.6 —— 那就是剩下的"抖动"。
        //   现在：向"前方一个角度"的点追踪（切向引导强），径向误差用带限幅的阻尼项慢慢收；
        //   相位仍按 ω = v/R 匀速前进，所以整套动作依然是确定性的几何。
        if (!this.arcInited) {
            // 从"当前方位"接着走，不跳相位
            this.arcPhase = Math.atan2(this.dragon.getZ() - target.getZ(),
                    this.dragon.getX() - target.getX());
            this.arcInited = true;
        }
        this.arcPhase += this.orbitSign * (speed / radius);
        this.arcRadius = radius;
        double dx = this.dragon.getX() - target.getX();
        double dz = this.dragon.getZ() - target.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        // 向前看的角度：半径误差越大看得越远（追回圆上更"果断"），限制在 20°~70°
        double err = radius - len;
        double lookahead = Mth.clamp(0.45D + Math.abs(err) * 0.05D, 0.35D, 1.2D);
        double aimed = this.arcPhase + this.orbitSign * lookahead;
        double wx = target.getX() + Math.cos(aimed) * radius;
        double wz = target.getZ() + Math.sin(aimed) * radius;
        Vec3 to = new Vec3(wx - this.dragon.getX(), 0.0D, wz - this.dragon.getZ());
        double dist = to.length();
        double vx = 0.0D;
        double vz = 0.0D;
        if (dist > 1.0E-3D) {
            vx = to.x / dist * speed;
            vz = to.z / dist * speed;
        }
        // 阻尼径向修正（限幅到 40% 速度）：把半径误差平滑压回 0，不会像 sign() 那样来回翻
        if (len > 1.0E-3D) {
            double radial = Mth.clamp(err * 0.12D, -speed * 0.4D, speed * 0.4D);
            vx += dx / len * radial;
            vz += dz / len * radial;
        }
        this.dragon.setKeepAim(true);
        this.dragon.setBodyRollDirect(ORBIT_BANK * this.orbitSign);
        this.dragon.setDiveVelocity(new Vec3(vx, this.hoverDelta(), vz));
    }

    /** 弧线半径相对 {@code orbitRadius()} 的放大系数（见 breathMotion 的说明）。 */
    private static final double ARC_RADIUS_FACTOR = 1.3D;

    /** 脚本弧线的相位 / 半径 / 是否已初始化（进入 BREATH 时复位）。 */
    private double arcPhase;
    private double arcRadius;
    private boolean arcInited;

    /** 这一 tick 的锥体长度（格）：1 倍基准 × 体型倍率。 */
    private double coneLength() {
        return CONE_LENGTH * this.dragon.combatGeoScale();
    }

    /**
     * 连续空轮后改用的"站桩"模式：不再绕圈，就近距离原地喷。
     *
     * <p>真机：绕圈攻击连续打不到 → 收招 → 重新靠拢 → 又绕同一圈 → 还是打不到（死循环）。
     * 现在连续 2 轮空就换几何：绕圈改站桩、距离压到 75%。
     */
    private boolean hoverOnly;
    /** 空轮后改站桩时，喷息距离压缩到正常值的多少倍。 */
    private static final double RETRY_RANGE_FACTOR = 0.75D;

    /** 喷息时视线被挡的连续 tick 数（超过 {@link #LOS_LOST_MAX} 就提前收招换位置）。 */
    private int noLosTicks;
    /** 视线被挡多少 tick 之后结束这一轮喷息（10 = 0.5 秒）。 */
    private static final int LOS_LOST_MAX = 10;

    /**
     * 朝一个水平点飞（保持悬停高度），转向交给实体的 faceMovement。
     *
     * <p>★ B 档寻路：<b>靠拢也走 A* 的航路点</b>。目标在墙后时，旧写法是直线撞墙
     * （"隔着墙找目标"），现在由路线绕过去；算不出路线时 {@code steer()} 原样返回目标点，
     * 也就是退回旧行为。速度仍按"离最终目标还有多远"决定，贴着航路点飞不会掉到最低速。
     */
    private void moveToward(Vec3 point, double speed) {
        Vec3 steer = this.dragon.pathFollower().steer(new Vec3(point.x, this.dragon.getY(), point.z), 1);
        double dx = steer.x - this.dragon.getX();
        double dz = steer.z - this.dragon.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        Vec3 velocity;
        if (len < 0.05D) {
            velocity = Vec3.ZERO;
        } else {
            velocity = new Vec3(dx / len * speed, 0.0D, dz / len * speed);
        }
        // 轨迹平滑：靠拢/退开途中目标一换向，位移不会瞬间反过去（配合飞行辅助的避障）
        this.dragon.steerVelocitySmooth(new Vec3(velocity.x, this.hoverDelta(), velocity.z),
                DragonGolemConfig.trajectoryLerp());
    }

    /**
     * 维持离地高度的竖直速度（和待机/俯冲拉升同一套写法）。上限跟着速度倍率放大，否则高速时爬不动。
     *
     * <p>★ 高度与寻路统合：目标高度不再死认悬停高度，而是问
     * {@link DragonGolemEntity#flightVerticalTarget} ——
     * 当航路点明显更高/更低（翻梁、爬出低房间）时跟着航线爬/降，平时仍是悬停高度。
     * {@code holdHover()}（前摇/收尾）保持纯悬停，不被航线带跑。
     */
    private double hoverDelta() {
        // 统一交给实体的唯一竖直跟踪器（高度目标已由 altitudeField 保证可行）
        return this.dragon.trackedVerticalSpeed();
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
        // ★ 跟踪率要留足余量：绕圈时目标方位以 ω = v / r 的角速度变化，
        //   如果 aimAt 的上限只比它略大，就会"先落后、再一口气追上"—— 真机上表现为
        //   yaw 速率忽快忽慢（[breath] 里 10.5°/tick 之后又 1.6°/tick），看起来就是抽搐 + 侧着喷。
        //   这里给 2.5× 余量（下限 15°/tick）：跟踪永远不落后，也就没有"追赶爆发"。
        float rate = Math.max(AIM_TURN * 2.5F, this.dragon.turnRateFor(horizontal) * 1.5F);
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
            // 视线计数必须每轮复位：否则上一轮的"被挡 20 tick"会带进下一轮，
            // 让下一轮刚开始就立刻收招（真机：`[breath] 视线被挡 13..20 tick` 每 tick 刷一行）。
            this.noLosTicks = 0;
            // 脚本弧线每轮重新起相（从当前方位接着走，不跳相位）
            this.arcInited = false;
        }
        // 只有开火阶段才上报姿态；靠拢阶段保持水平（否则会一直低着头飞）
        this.dragon.setDivePhase(next == Phase.CLOSE_IN
                ? DragonGolemEntity.DIVE_PHASE_NONE
                : DragonGolemEntity.DIVE_PHASE_BREATH);
        if (DEBUG) {
            Dragon_golems.LOGGER.info("[ranged] phase={} target={}", next,
                    this.dragon.getTarget() == null ? "null" : this.dragon.getTarget().getName().getString());
        }
    }
}

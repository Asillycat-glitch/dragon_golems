package a_silly_cat.dragon_golems.dragon;

import a_silly_cat.dragon_golems.Dragon_golems;
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
                && this.dragon.hasLineOfSight(target));
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
        boolean los = this.dragon.hasLineOfSight(target);
        if (skill == DragonGolemEntity.DragonSkill.BREATH
                && dist <= this.dragon.breathRange() && los) {
            // 每次开喷随机挑一个绕行方向（顺时针/逆时针），免得永远往同一边转
            this.orbitSign = this.dragon.getRandom().nextBoolean() ? 1 : -1;
            this.dragon.onSkillStarted(skill);
            this.enter(Phase.BREATH_CHARGE);
        } else if (skill == DragonGolemEntity.DragonSkill.ROCKET && dist <= ROCKET_RANGE && los) {
            this.dragon.onSkillStarted(skill);
            this.enter(Phase.ROCKET_AIM);
        } else {
            // 没抽中、或条件不满足 → 这一趟只负责飞过去
            this.enter(Phase.CLOSE_IN);
        }
        this.dragon.setAggressive(true);
    }

    @Override
    public void stop() {
        this.dragon.setDiveVelocity(null);
        this.dragon.setDivePhase(DragonGolemEntity.DIVE_PHASE_NONE);
        this.dragon.setAggressive(false);
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
        boolean los = this.dragon.hasLineOfSight(target);
        DragonGolemEntity.DragonSkill skill = this.dragon.pendingSkill();
        if (skill == DragonGolemEntity.DragonSkill.BREATH
                && dist <= this.dragon.breathRange() && los) {
            this.orbitSign = this.dragon.getRandom().nextBoolean() ? 1 : -1;
            this.dragon.onSkillStarted(skill);
            this.enter(Phase.BREATH_CHARGE);
            return;
        }
        if (skill == DragonGolemEntity.DragonSkill.ROCKET && dist <= ROCKET_RANGE && los) {
            this.dragon.onSkillStarted(skill);
            this.enter(Phase.ROCKET_AIM);
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
        Vec3 origin = this.dragon.mouthPosition();
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
    private void breathMotion(LivingEntity target) {
        if (!BREATH_WHILE_FLYING) {
            this.holdHover();
            return;
        }
        double dx = this.dragon.getX() - target.getX();
        double dz = this.dragon.getZ() - target.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        double speed = ORBIT_SPEED * this.dragon.flightSpeedFactor();
        double vx = 0.0D;
        double vz = 0.0D;
        if (len > 1.0E-3D) {
            double tx = -dz / len * this.orbitSign;
            double tz = dx / len * this.orbitSign;
            double radial = Mth.clamp((this.dragon.orbitRadius() - len) * 0.08D, -0.3D, 0.3D);
            vx = tx * speed + dx / len * radial;
            vz = tz * speed + dz / len * radial;
        }
        this.dragon.setKeepAim(true);
        this.dragon.setBodyRollDirect(ORBIT_BANK * this.orbitSign);
        this.dragon.setDiveVelocity(new Vec3(vx, this.hoverDelta(), vz));
    }

    /** 这一 tick 的锥体长度（格）：1 倍基准 × 体型倍率。 */
    private double coneLength() {
        return CONE_LENGTH * this.dragon.combatGeoScale();
    }

    /** 朝一个水平点飞（保持悬停高度），转向交给实体的 faceMovement。 */
    private void moveToward(Vec3 point, double speed) {
        double dx = point.x - this.dragon.getX();
        double dz = point.z - this.dragon.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        Vec3 velocity;
        if (len < 0.05D) {
            velocity = Vec3.ZERO;
        } else {
            velocity = new Vec3(dx / len * speed, 0.0D, dz / len * speed);
        }
        this.dragon.setDiveVelocity(new Vec3(velocity.x, this.hoverDelta(), velocity.z));
    }

    /** 维持离地高度的竖直速度（和待机/俯冲拉升同一套写法）。上限跟着速度倍率放大，否则高速时爬不动。 */
    private double hoverDelta() {
        double wantY = this.dragon.hoverTargetY();
        double cap = 0.18D * this.dragon.speedScale();
        return Mth.clamp((wantY - this.dragon.getY()) * 0.10D, -cap, cap);
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

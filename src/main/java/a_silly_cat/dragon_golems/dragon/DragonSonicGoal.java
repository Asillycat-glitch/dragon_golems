package a_silly_cat.dragon_golems.dragon;

import dev.xkmc.modulargolems.init.data.MGDamageTypes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * 龙的「音爆」：中距离的<b>瞬时线状穿透</b>攻击，和龙弹共享一条冷却
 * （见 {@code DragonGolemEntity.blastCooldown}）。
 *
 * <p><b>只有"音波系"的龙才有这一招</b>（身体部件用幽匿，见 {@code DragonGolemEntity.isSonicBody()}）：
 * 音波在设定上是幽匿 / 监守者的东西，铁龙和下界龙喷的是火。所以实际的招表是：
 * <ul>
 *   <li><b>幽匿龙 = 音波龙</b>：常态是音波锥（龙息音波化）、大招是这条线状光束；</li>
 *   <li>其它材料：俯冲 / 龙息 / 龙弹，三招。</li>
 * </ul>
 *
 * <p>命中逻辑照抄本家回响炮 {@code SonicCannonBehavior.shoot()}：沿瞄准轴<b>每 1 格取一个采样点</b>，
 * 谁的包围盒（外扩 {@link #HIT_INFLATE}）被采样点命中就打谁 —— 所以它是"一条线上的所有目标"，
 * 既不是单体、也不是龙息那种锥形。<b>能不能群攻只取决于这段判定代码，和伤害类型无关</b>，
 * 换成原版 {@code sonic_boom} 一样是一条线全打。
 *
 * <p>和回响炮的三点差别：
 * <ul>
 *   <li><b>光束长度按体型缩放</b>（{@code dragon.sonicBeamLength()}）：回响炮硬写 17 格，
 *       对泰坦体型那种一百多格长的龙来说，等于从嘴里伸出去一截就没了；</li>
 *   <li>伤害类型是我们自己的选择（现在是 MG 的 {@code modulargolems:echo_attack}，
 *       和回响炮一致；龙息那条常态通道用的是原版 {@code sonic_boom}，见 {@code breathDamage()}）；</li>
 *   <li>距离不够时这个 goal <b>自己飞过去</b>（回响炮那套交给武器 AI），
 *       所以抽中音爆就一定会打出去，不会因为"超出射程"被白白重掷。</li>
 * </ul>
 *
 * <p>优先级 2（和俯冲并列，都是"被骰子抽中才上"的大招）。必须低于远程 goal(3)：
 * 这一轮抽中的是音爆时，要从龙息/靠拢那里把 MOVE 抢过来。两个 2 之间不会打架 ——
 * 各自的 {@code canUse()} 只看 {@code wantsSkill(自己的技能)}，而 {@code pendingSkill} 只有一个值。
 */
public class DragonSonicGoal extends Goal {

    private enum Phase {
        /** 距离不够，先飞过去对准。 */
        CLOSE_IN,
        /** 前摇：转头、嘴里聚粒子。 */
        AIM,
        /** 收尾。 */
        RECOVER
    }

    /** 前摇时长（tick）。 */
    private static final int AIM_TICKS = 12;
    private static final int RECOVER_TICKS = 10;
    /** 靠拢速度（格/tick，会乘飞行速度倍率）与靠拢超时（tick）。 */
    private static final double CLOSE_SPEED = 0.55D;
    private static final int CLOSE_MAX = 200;
    /** 瞄准转速的下限（度/tick）；实际转速复用实体那套"按速度放大"的公式。 */
    private static final float AIM_TURN = 6.0F;
    /** 命中判定时把目标包围盒外扩多少格（和回响炮一致）。 */
    private static final double HIT_INFLATE = 1.0D;

    // ---- 伤害（公式照抄回响炮，倍率是我们的旋钮）----
    /** 伤害 = {@code max(DAMAGE_MIN, 龙的攻击力 × DAMAGE_FACTOR)}。 */
    private static final float DAMAGE_FACTOR = 1.0F;
    private static final float DAMAGE_MIN = 10.0F;
    /** 击退（和回响炮一致：水平 2.5、竖直 0.5，都会乘目标的击退抗性减免）。 */
    private static final double KNOCKBACK_H = 2.5D;
    private static final double KNOCKBACK_V = 0.5D;

    private final DragonGolemEntity dragon;
    private Phase phase = Phase.AIM;
    private int phaseTicks;
    /** 跑完一整轮就置 true，让 GoalSelector 收手（和另外两个 goal 同一个套路）。 */
    private boolean finished;
    /**
     * 这一轮<b>真的打出去了</b>。
     *
     * <p>{@link #stop()} 只在这个为真时才上报技能完成 —— 靠拢到一半目标飞了、或者被更高优先级的
     * goal 叫停，都不该白吃一次共享冷却。
     */
    private boolean fired;

    public DragonSonicGoal(DragonGolemEntity dragon) {
        this.dragon = dragon;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        // 只有这一轮被骰子抽中"音爆"时才上（冷却由实体统一管，见 DragonGolemEntity.tickSkillRoll）
        if (!this.dragon.wantsSkill(DragonGolemEntity.DragonSkill.SONIC)) {
            return false;
        }
        // 音波系专属：只有身体用幽匿的龙才有这一招（铁龙、下界龙喷的是火）
        if (!this.dragon.isSonicBody()) {
            return false;
        }
        if (!this.dragon.isMovable() || this.dragon.isInSittingPose()) {
            return false;
        }
        if (this.dragon.getControllingPassenger() != null) {
            return false;
        }
        LivingEntity target = this.dragon.getTarget();
        return target != null && target.isAlive();
    }

    /**
     * 有人骑上来就立刻中断这一轮音爆（理由和写法见 {@code DragonDiveGoal.canContinueToUse}：
     * {@link #canUse()} 只挡"开始"，挡不住"已经跑起来的"）。
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
        this.fired = false;
        // 告诉调度器"这一轮真的开打了"：靠拢可能要跑几十 tick，
        // 不暂停待办超时的话，会被自己的 60 tick 重掷窗口清掉。
        this.dragon.onSkillStarted(DragonGolemEntity.DragonSkill.SONIC);
        this.dragon.setAggressive(true);
        this.enter(this.inBeamRange() ? Phase.AIM : Phase.CLOSE_IN);
    }

    @Override
    public void stop() {
        this.dragon.setDiveVelocity(null);
        this.dragon.setDivePhase(DragonGolemEntity.DIVE_PHASE_NONE);
        this.dragon.setAggressive(false);
        // 只有真的打出去才上报：靠拢那趟什么都没打出去，不能白吃一次共享冷却
        if (this.fired) {
            this.dragon.onSkillFinished(DragonGolemEntity.DragonSkill.SONIC);
            this.fired = false;
        }
    }

    @Override
    public void tick() {
        LivingEntity target = this.dragon.getTarget();
        if (target == null || !target.isAlive()) {
            this.finish();
            return;
        }
        this.phaseTicks++;
        // if/else 代替 enum switch：避免 javac 生成 DragonSonicGoal$1 那个 $SwitchMap 合成类
        // （合成类加载不到会在实体 tick 里抛 NoClassDefFoundError 直接崩档，另外两个 goal 同理）。
        if (this.phase == Phase.CLOSE_IN) {
            this.tickCloseIn(target);
        } else if (this.phase == Phase.AIM) {
            this.tickAim(target);
        } else {
            this.tickRecover();
        }
    }

    // ---- 各阶段 ----

    /** 距离不够：一边对准一边飞过去，进了光束长度就转前摇。 */
    private void tickCloseIn(LivingEntity target) {
        this.aim(target);
        this.moveToward(target, CLOSE_SPEED * this.dragon.flightSpeedFactor());
        if (this.inBeamRange() || this.phaseTicks > CLOSE_MAX) {
            this.enter(Phase.AIM);
        }
    }

    /** 前摇：站住不动、转头、嘴里聚粒子，然后打出去。 */
    private void tickAim(LivingEntity target) {
        this.aim(target);
        this.holdHover();
        if (this.phaseTicks == 1) {
            this.dragon.playSound(SoundEvents.WARDEN_SONIC_CHARGE, 3.0F, 1.0F);
        }
        if (this.dragon.level() instanceof ServerLevel level) {
            Vec3 mouth = this.dragon.mouthPosition();
            // 越接近喷出越收紧（和龙息前摇同一套观感）
            double radius = Mth.clamp(0.9D - 0.7D * (this.phaseTicks / (double) AIM_TICKS), 0.2D, 0.9D);
            level.sendParticles(this.dragon.breathParticle(), mouth.x, mouth.y, mouth.z, 2,
                    radius, radius, radius, 0.0D);
        }
        if (this.phaseTicks >= AIM_TICKS) {
            this.fire(target);
            this.enter(Phase.RECOVER);
        }
    }

    private void tickRecover() {
        this.holdHover();
        if (this.phaseTicks >= RECOVER_TICKS) {
            this.finish();
        }
    }

    // ---- 开火 ----

    /**
     * 打出去：沿瞄准轴每格取一个采样点，谁的包围盒被采样点命中就打谁。
     *
     * <p>这套算法是从本家回响炮 {@code SonicCannonBehavior.shoot()} 抄的（连外扩 1 格、
     * 击退 2.5/0.5 都一样），只把"光束长度"换成了按体型缩放的值、把"伤害倍率"换成了我们的常量，
     * 并且多了一层 {@code predicateTarget} 过滤 —— 回响炮靠武器 AI 保证只打敌人，
     * 我们得自己挡掉主人和友军。
     */
    private void fire(LivingEntity target) {
        if (!(this.dragon.level() instanceof ServerLevel level)) {
            return;
        }
        Vec3 src = this.dragon.mouthPosition();
        Vec3 to = target.getEyePosition().subtract(src);
        Vec3 dir = to.lengthSqr() < 1.0E-6D ? this.dragon.getLookAngle() : to.normalize();
        int len = Math.max(1, Mth.floor(this.dragon.sonicBeamLength()));
        this.fired = true;

        // 光束粒子：每格一颗 SONIC_BOOM（和回响炮、L2 音爆枪都是同一套观感）
        for (int i = 1; i < len; i++) {
            Vec3 at = src.add(dir.scale(i));
            level.sendParticles(ParticleTypes.SONIC_BOOM, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        level.playSound(null, this.dragon.blockPosition(), SoundEvents.WARDEN_SONIC_BOOM,
                SoundSource.HOSTILE, 3.0F, 1.0F);

        // 线状穿透判定
        List<LivingEntity> victims = new ArrayList<>();
        AABB area = new AABB(src, src.add(dir.scale(len)));
        for (Entity e : level.getEntities(this.dragon, area)) {
            if (!(e instanceof LivingEntity victim) || !victim.isAlive() || victim.isSpectator()) {
                continue;
            }
            if (this.dragon.hasPassenger(victim) || victim.isPassengerOfSameVehicle(this.dragon)) {
                continue;
            }
            if (!this.dragon.predicateTarget(victim) || !this.dragon.canAttack(victim)) {
                continue;
            }
            AABB box = victim.getBoundingBox().inflate(HIT_INFLATE);
            for (int i = 0; i <= len; i++) {
                if (box.contains(src.add(dir.scale(i)))) {
                    victims.add(victim);
                    break;
                }
            }
        }
        if (victims.isEmpty()) {
            return;
        }
        DamageSource source = this.echoSource(level);
        float damage = Math.max(DAMAGE_MIN,
                (float) this.dragon.getAttributeValue(Attributes.ATTACK_DAMAGE) * DAMAGE_FACTOR);
        for (LivingEntity victim : victims) {
            // 走统一技能伤害出口：过一遍升级挂载点（命中特效 / 击杀特效），并保留目标原本的无敌帧
            this.dragon.dealSkillDamage(victim, source, damage, 0.0D);
            // 击退抗性只用来"削"击退：夹在 0 以上，抗性拉满就是完全不推（原版 LivingEntity#knockback
            // 也是这个语义，只不过它靠 `<= 0` 提前返回，而 Entity#push 没有那道守卫）
            double resist = Math.max(0.0D, 1.0D - victim.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
            victim.push(dir.x * KNOCKBACK_H * resist, dir.y * KNOCKBACK_V * resist,
                    dir.z * KNOCKBACK_H * resist);
        }
    }

    /**
     * 回响伤害（{@code modulargolems:echo_attack}）：穿护甲、附魔、抗性、无敌帧的真伤，
     * 和回响炮用的是同一条伤害类型。
     *
     * <p>按 <b>id</b> 从注册表里取、取不到就退回原版 {@code sonic_boom}：
     * 免得第三方（mgdp 之类）动过伤害类型注册时抛异常把这一招直接打哑。
     */
    private DamageSource echoSource(ServerLevel level) {
        try {
            return new DamageSource(
                    level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
                            .getOrThrow(MGDamageTypes.ECHO),
                    this.dragon);
        } catch (RuntimeException e) {
            return level.damageSources().sonicBoom(this.dragon);
        }
    }

    // ---- 杂项 ----

    /** 目标是否已经在光束长度之内。 */
    private boolean inBeamRange() {
        LivingEntity target = this.dragon.getTarget();
        return target != null && this.dragon.distanceTo(target) <= this.dragon.sonicBeamLength();
    }

    /** 把身子转向目标（和 faceMovement 共用 yRot/yBodyRot 的同步规则）。 */
    private void aim(LivingEntity target) {
        double horizontal = this.dragon.getDeltaMovement().horizontalDistance();
        float rate = Math.max(AIM_TURN, this.dragon.turnRateFor(horizontal));
        this.dragon.aimAt(target.getX(), target.getZ(), rate);
    }

    /** 悬停：水平不动，只维持离地高度（和远程 goal 同一套写法）。 */
    private void holdHover() {
        this.dragon.setDiveVelocity(new Vec3(0.0D, this.hoverDelta(), 0.0D));
    }

    /** 朝目标飞（保持悬停高度）；转向交给实体的 faceMovement。 */
    private void moveToward(LivingEntity target, double speed) {
        Vec3 delta = new Vec3(target.getX(), this.dragon.getY(), target.getZ())
                .subtract(this.dragon.position());
        double len = delta.length();
        Vec3 velocity = len < 0.05D ? Vec3.ZERO : delta.scale(speed / len);
        this.dragon.setDiveVelocity(new Vec3(velocity.x, this.hoverDelta(), velocity.z));
    }

    /** 维持离地高度的竖直速度（上限跟着速度倍率放大，和远程/待机一致）。 */
    private double hoverDelta() {
        double cap = 0.18D * this.dragon.speedScale();
        return Mth.clamp((this.dragon.hoverTargetY() - this.dragon.getY()) * 0.10D, -cap, cap);
    }

    private void enter(Phase next) {
        this.phase = next;
        this.phaseTicks = 0;
        // 前摇/收尾都算"开火姿态"：模型会低头 10° 并张嘴（见 DragonGolemModel 的 jaw 那段）
        this.dragon.setDivePhase(next == Phase.CLOSE_IN
                ? DragonGolemEntity.DIVE_PHASE_NONE
                : DragonGolemEntity.DIVE_PHASE_BREATH);
    }

    private void finish() {
        this.finished = true;
        this.stop();
    }
}
